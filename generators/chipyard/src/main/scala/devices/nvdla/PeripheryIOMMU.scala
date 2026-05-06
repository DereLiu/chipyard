// See LICENSE for license details.
package chipyard

import chisel3._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.diplomacy.{
  LazyModule,
  BufferParams,
  AddressSet,
  Description,
  Device,
  DeviceSnippet,
  Resource,
  ResourceBinding,
  ResourceInt,
  ResourceReference,
  ResourceBindings,
  ResourceAddress,
  ResourcePermissions,
  ResourceAnchors,
  SimpleDevice
}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.amba.axi4._

import nvidia.blocks.dla.{NVDLA, NVDLAKey, NVDLAFrontBusExtraBuffers, NVDLAReservedMemKey}
import zerodaylabs.blocks.iommu.CanHavePeripheryIOMMU
import freechips.rocketchip.devices.tilelink.{TLError, DevNullParams}

/**
  * Attach NVDLA through the IOMMU's device-translation AXI slave when available.
  * Falls back to the original front-bus attachment if IOMMU is not present or
  * not exposing the device-translation port.
  */
trait CanHavePeripheryNVDLAThroughIOMMU { this: BaseSubsystem with CanHavePeripheryIOMMU =>

  p(NVDLAKey).foreach { params =>
    val nvdla = LazyModule(new NVDLA(params))

    // Always attach NVDLA's config + interrupt like before
    pbus.coupleTo("nvdla_cfg") {
      nvdla.cfg_tl_node :=
        // Limit outstanding source IDs to avoid mismatches with downstream bridges
        TLSourceShrinker(8) :=
        TLFragmenter(4, pbus.blockBytes) :=
        TLWidthWidget(pbus.beatBytes) :=
        TLBuffer(BufferParams.default) := _
    }
    ibus.fromSync := nvdla.int_node

    // Optional reserved-memory region advertised to NVDLA
    val nvdlaResvRoot = p(NVDLAReservedMemKey).map { _ =>
      new DeviceSnippet {
        override def parent = None
        def describe(): Description = Description("reserved-memory", Map(
          "#address-cells" -> Seq(ResourceInt(2)),
          "#size-cells"    -> Seq(ResourceInt(2)),
          "ranges"         -> Nil))
      }
    }

    p(NVDLAReservedMemKey).foreach { resv =>
      val region = new SimpleDevice("nvdla_reserved", Seq("shared-dma-pool")) {
        override def parent = Some(nvdlaResvRoot.getOrElse(super.parent.get))
        override def describe(resources: ResourceBindings): Description = {
          val Description(name, mapping) = super.describe(resources)
          Description(name, mapping ++ Map("no-map" -> Nil))
        }
      }
      val addrSets = AddressSet.misaligned(resv.base, resv.size)
      val perms = ResourcePermissions(r = true, w = true, x = false, c = false, a = false)
      ResourceBinding {
        Resource(region, "reg").bind(ResourceAddress(addrSets, perms))
        Resource(nvdla.dtsdevice, "memory-region").bind(ResourceReference(region.label))
      }
    }

    // Prefer routing NVDLA DBB through IOMMU dev-slave if exposed
    (for {
      iommu   <- iommuOpt
      devNode <- iommu.devNodeOpt
    } yield (iommu, devNode)).map { case (iommu, devNode) =>
      // Ensure a TLError is reachable from the internal AXI4ToTL in NVDLA
      // by inserting a small TL xbar with an error device alongside the TLToAXI4 path.
      val beatBytes = if (params.config == "large") 32 else 8
      val devAddrBits = iommu.params.devAddrBits
      // Reserve a small window at the top of the IOMMU-visible range for the TLError
      // so that the TL managers presented to AXI4ToTL remain disjoint even when the
      // device-translation slave spans the full address space.
      val maxDevAddr  = (BigInt(1) << devAddrBits) - 1
      val errorWindowBits =
        if (devAddrBits <= 1) 0 else math.min(devAddrBits - 1, 12)
      val errorMask =
        if (errorWindowBits == 0) BigInt(0) else (BigInt(1) << errorWindowBits) - 1
      val errorBase  = maxDevAddr & ~errorMask
      val errorSet   = AddressSet(errorBase, errorMask)
      val errParams = DevNullParams(
        address     = Seq(errorSet),
        maxAtomic   = beatBytes,
        maxTransfer = 256)
      val errorDev = LazyModule(new TLError(errParams, beatBytes = beatBytes))
      val tlFilter = LazyModule(new TLFilter(TLFilter.mSubtract(errorSet)))
      val tlx = TLXbar()

      // Insert FIFO fixer upstream of the xbar so all downstream managers
      // (error device and TL->AXI path) share a homogeneous fifoId
      val fifoFix = TLFIFOFixer(TLFIFOFixer.all)
      tlx := fifoFix := TLBuffer.chainNode(p(NVDLAFrontBusExtraBuffers)) := nvdla.dbb_tl_node
      // Provide a reachable error device for AXI4ToTL's requirement
      errorDev.node := tlx
      // Route to IOMMU dev-translation via TL->AXI.
      // Keep only one translated NVDLA request in flight.  TLToAXI4 maps a
      // requestFifo TL client to one AXI ID with maxFlight equal to the TL
      // source count; a larger shrinker therefore sends many same-ID requests
      // into the IOMMU dev port.  The OpenDLA path is sensitive to same-ID
      // response ordering, so use the conservative single-flight path here.
      devNode :=
        AXI4Buffer() :=
        TLToAXI4(adapterName = Some("iommu_dev_trans")) :=
        TLSourceShrinker(1) :=
        TLBuffer() :=
        tlFilter.node :=
        tlx
      // Advertise the IOMMU association in DTS so Linux links the master to it
      ResourceBinding {
        Resource(nvdla.dtsdevice, "iommus").bind(ResourceReference(iommu.dtsdevice.label))
        Resource(nvdla.dtsdevice, "iommus").bind(ResourceInt(BigInt(iommu.params.devDefaultSid)))
      }
    }.getOrElse {
      // Fallback: original front-bus attachment
      fbus.coupleFrom("nvdla_dbb") {
        _ := TLBuffer(BufferParams.default) :=*
          TLFIFOFixer(TLFIFOFixer.all) :=*
          TLBuffer.chainNode(p(NVDLAFrontBusExtraBuffers)) :=*
          nvdla.dbb_tl_node
      }
    }
  }
}
