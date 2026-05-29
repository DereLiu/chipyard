# Chipyard-IOMMU: RISC-V IOMMU Integration for NVDLA/Chipyard

This repository contains a Chipyard-based RISC-V heterogeneous SoC platform
with an integrated RISC-V IOMMU and NVDLA accelerator. It was developed for
evaluating IOMMU-enforced DMA isolation and TEE-oriented secure I/O on FPGA.

The platform connects NVDLA-initiated DMA traffic to a RISC-V IOMMU before
the requests reach system memory. The CPU configures the IOMMU through MMIO,
while the accelerator uses IOVA-based DMA mappings managed by the Linux
IOMMU/DMA software stack.

This repository is based on Chipyard. The original Chipyard README has been
preserved as [`README-Chipyard.md`](README-Chipyard.md).

## Overview

The main goal of this repository is to provide an end-to-end hardware/software
evaluation platform for RISC-V IOMMU-based accelerator DMA isolation.

The platform includes:

- Chipyard-based RISC-V SoC generation.
- NVDLA accelerator integration.
- RISC-V IOMMU integration as a SystemVerilog BlackBox.
- AXI/TileLink bridge logic for connecting the IOMMU to the Chipyard memory
  system.
- Device-tree and hardware configuration support for attaching NVDLA to the
  IOMMU-mediated DMA path.
- FPGA-oriented configurations for Xilinx VCU118 evaluation.
- Support for studying DMA isolation, IOMMU faults, IOTLB behavior, page-size
  effects, and end-to-end DMA-path overhead.

## Repository Structure

Important project-specific files and directories include:

```text
generators/iommu/
  Chipyard-side RISC-V IOMMU wrapper, BlackBox binding, and integrated
  SystemVerilog source files.

generators/iommu/src/main/scala/IOMMU.scala
  Chipyard Diplomacy wrapper, IOMMU parameter definitions, device-tree
  description, AXI slave/master nodes, interrupt connection, and Config
  fragments.

generators/iommu/src/main/scala/ip/iommu.scala
  Chisel BlackBox interface for the RISC-V IOMMU SystemVerilog module.

generators/iommu/src/main/resources/
  RISC-V IOMMU SystemVerilog source files, preprocessing scripts, and
  bundled RTL resources.

fpga/src/main/scala/vcu118/Configs.scala
  VCU118 FPGA configurations, including Rocket/NVDLA/IOMMU configurations.

README-Chipyard.md
  Original Chipyard README.
```

## Build

We have confirmed execution of the built bitstream only on a VCU118 board.
Start from the IOMMU branch and initialize the submodules before building:

```bash
git checkout feature/iommu-submodule
git submodule update --init --recursive
```

For FPGA evaluation on VCU118, use one of the IOMMU-enabled VCU118 configs.
The single-core Rocket/NVDLA/IOMMU configuration is the default bring-up target:

```bash
cd fpga
make SUB_PROJECT=vcu118 CONFIG=IOMMUVCU118SmallNVDLARocketConfig bitstream
```

Other available VCU118 IOMMU configurations include:

```text
IOMMUVCU118SmallNVDLAQuadRocketConfig
IOMMUVCU118SmallNVDLACVA6Config
```

For Verilator elaboration or software simulation, use the Chipyard simulator
flow with the matching Chipyard-level config:

```bash
cd sims/verilator
make CONFIG=SmallNVDLAIOMMURocketConfig
```

## Using the IOMMU

The IOMMU is integrated through `generators/iommu` as a SystemVerilog BlackBox.
The `SmallNVDLAIOMMURocketConfig` and `SmallNVDLAIOMMUQuadRocketConfig`
configs instantiate the IOMMU device-translation slave at `0x50010000` and
route NVDLA DBB DMA traffic through it before requests reach memory.

NVDLA remains configured through its normal MMIO path. Its DMA path is the part
mediated by the IOMMU. The generated device tree binds the NVDLA master to the
IOMMU using the `iommus` property, so Linux can associate NVDLA DMA with the
RISC-V IOMMU driver and allocate IOVA mappings through the normal DMA/IOMMU
stack.

Bare-metal test programs are under `tests/`. They can be built with:

```bash
cd tests
make
```

For simulation, pass a built bare-metal binary to the Verilator run target, for
example:

```bash
cd sims/verilator
make CONFIG=SmallNVDLAIOMMURocketConfig \
  BINARY=../../tests/nvdla.riscv \
  run-binary-fast
```

For Linux-based experiments, boot a workload with the IOMMU-enabled hardware
configuration, confirm that the generated device tree contains the IOMMU node
and the NVDLA `iommus` binding, and then run the NVDLA workload through the
Linux DMA API path. IOMMU faults, mappings, and DMA translation behavior should
be checked from the guest kernel logs and the IOMMU driver state.
