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
