[![Build and Release](https://github.com/ITS-Simulation/MATSim-Bus-Optimizer/actions/workflows/release.yml/badge.svg)](https://github.com/ITS-Simulation/MATSim-Bus-Optimizer/actions/workflows/release.yml)
[![GitHub Release](https://img.shields.io/github/v/release/ITS-Simulation/MATSim-Bus-Optimizer?label=Latest%20Release&logo=github&display_name=release)](https://github.com/ITS-Simulation/MATSim-Bus-Optimizer/releases/tag/v2.15.2)

# MATSim Bus Optimizer
[🇻🇳 Tiếng Việt](README_VI.md)

A Kotlin-based simulation and scoring pipeline for transit scenarios in MATSim simulations. Using Apache Arrow for efficient data storage, DuckDB for high-performance data processing, and calculates KPI scores based on TCRP standards.

## Features

- **Multi-mode execution** — Simulation (`sim`), offline analysis (`analysis`), simple run (`simple-run`), and Arrow-to-CSV conversion (`arrow`)
- **Real-time event processing** — Online event handlers capture transit data during MATSim simulation without generating large XML files
- **Offline batch analysis** — Re-analyze existing MATSim XML event files with a high-performance streaming parser
- **10-component TCRP scoring** — Weighted scoring system based on transit quality metrics (ridership, on-time performance, productivity, etc.)
- **Dual output format** — Apache Arrow IPC (high-performance binary) and CSV (human-readable) output support
- **DuckDB analytics engine** — High-performance SQL-based analytics on transit simulation data
- **YAML-configurable** — Scoring weights, parameters, and data paths controlled via a single YAML config file
- **Binary + JSON score output** — Compact 8-byte binary score for pipeline integration, with optional JSON breakdown of all metrics
- **Automated CI/CD** — GitHub Actions for automatic version tagging, release builds, and cross-repo Dockerfile deployment
- **Coroutine-based async I/O** — Kotlin coroutines for efficient concurrent data writing
- **Structured logging** — Log4j2 with runtime-configurable log levels and custom worker signatures

## Techstacks
<p>
  <a href="https://kotlinlang.org/"><img alt="kotlin" src="https://img.shields.io/badge/-Kotlin-7F52FF?logo=kotlin&logoColor=white"/></a>
  <a href="https://openjdk.org/"><img alt="java" src="https://img.shields.io/badge/-Java%2021-ED8B00?logo=openjdk&logoColor=white"/></a>
  <a href="https://matsim.org/"><img alt="matsim" src="https://img.shields.io/badge/-MATSim-2C588E?logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxMDAgMTAwIj4KPHRleHQgeD0iNTAiIHk9Ijc1IiBmb250LWZhbWlseT0iQXJpYWwsc2Fucy1zZXJpZiIgZm9udC1zaXplPSI4MCIgZm9udC13ZWlnaHQ9ImJvbGQiIGZpbGw9IndoaXRlIiB0ZXh0LWFuY2hvcj0ibWlkZGxlIj5NPC90ZXh0Pgo8L3N2Zz4%3D"/></a>
  <a href="https://duckdb.org/"><img alt="duckdb" src="https://img.shields.io/badge/-DuckDB-FFF000?logo=duckdb&logoColor=black"/></a>
  <a href="https://arrow.apache.org/"><img alt="arrow" src="https://img.shields.io/badge/-Apache%20Arrow-3EC6B0?logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxMzUwIDExODEiPgo8cG9seWdvbiBwb2ludHM9IjE2OSwxNjkgNTkxLDU5MSAxNjksMTAxMyAxNjksODQ0IDQyMiw1OTEgMTY5LDMzOCIgZmlsbD0iI2ZmZiIvPgo8cG9seWdvbiBwb2ludHM9IjQ2NCwxNjkgODg2LDU5MSA0NjQsMTAxMyA0NjQsODQ0IDcxNyw1OTEgNDY0LDMzOCIgZmlsbD0iI2ZmZiIvPgo8cG9seWdvbiBwb2ludHM9Ijc1OSwxNjkgMTE4MSw1OTEgNzU5LDEwMTMgNzU5LDg0NCAxMDEzLDU5MSA3NTksMzM4IiBmaWxsPSIjZmZmIi8%2BCjwvc3ZnPg%3D%3D"/></a>
  <a href="https://gradle.org/"><img alt="gradle" src="https://img.shields.io/badge/-Gradle-02303A?logo=gradle&logoColor=white"/></a>
  <a href="https://www.docker.com/"><img alt="docker" src="https://img.shields.io/badge/-Docker-2496ED?logo=docker&logoColor=white"/></a>
  <a href="https://logging.apache.org/log4j/2.x/"><img alt="log4j" src="https://img.shields.io/badge/-Log4j2-D22128?logo=data%3Aimage%2Fpng%3Bbase64%2CiVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAYAAADgdz34AAAE1UlEQVR42rWWa2xURRTH%2F2fu3XYf7fYJtAWFKmisJg0CYmLjlpYiEStIaTEBYhBbmyjBoCV%2BQJbFRCTEL6iE6AfBaCJZUFF8xBfZICYoigisIAoUaLeUPna72%2B7ex8zxw7oQawuNxpNMcicz9%2F%2FPnJn5nSGMIRgg%2BP2EcJgAABUVjECACWD8l2CADvh8%2BmjjB3w%2BnQH6d%2BKNjdrVb2YRXV4%2Ftefh6tk9DXWzoy1Lb2VmMdLc4UGjiVMwKM8sXXhTfry7leMDi5TDMS3L6XKwlDCHBi09O%2Fs3kVf4wWBK7bj5k286Mv%2Fc0CAzseuRmieykoktOQVFhbSgAZhxL9iTw2ykoNrPkvx8H%2FQTR5HMdvakmNaVfhR6azSTf6Slc2H1ZqP%2BPu5rXspmV6dlnA7L%2Fi1%2B1dvWyn0b2zjx4W7FliXje961rtTNZKOhhjvq73%2FxuunKDHQsqGq2m%2Bo4UnWHOXTwa6niAxypmc7dy%2Bu5p3UZR2pn8KXpk7lvw1pmZu59frWM1E63rCW1fOmhqpXDTTQA8AOiOhzmVQtqJjqF%2FNiQSpcggURcUH4hHLdXQC%2BfBseUqfA0Loe8eA7mkcNwz18IIqbkwQOQThcTc01bZflOT%2FDTBAARAlgHgI0%2Bn6BQyO4ia7XXoef0GpYtXG7dOHwIKpEAuT0wvj8EEgLj934FrWQiZFcEWulE2BfOQ%2Bi6MJWyixya90pKPk3Aevb5tEAopCiz0ezzaV25MuzStKkpgDkRF87qechfvxnG4W%2FBhgFyOmG3n8PQ%2Fj3IW%2FsCKDsbfc8%2BCWgaGFAuIShpy9Ml7tK7EAwqAliw308A%2BEKBmExAuWGaxJZFLCVU7xUAQOLtNxANtCH52T5Yv5%2BCq%2FZBZFXOgHnyGGR%2FL8AMWBYZlkVEuCUieyYRwOyH0DPXPxtyfJYg3fTksaY7CEXFsNvPYmj%2F%2Byh4aRsGd%2B%2BCig%2BAXC4IrxecTEJGOqGXTQK5PYBiUqbBWalklmFzCYB2hBvpGgYcLnCsD951m%2BC8%2Bx5AyvTSk0mIvHzktqwZduwUclY0I3fVU4AtAV3D0Bf7kdr2MhwTyq4ySkdFMN0pLrpsXI7Y7nhMJ5ebwUwgArnc6RQoBRBl2AFoGkRe%2FlVogcCIRcm0pekcV9iVhmKQBQXADNCEpuYLIDqLn39gAAyl0kLMaWFNA4RIN027ZsQMsAIApuM%2FMuv6HwVV9R0MEAWgBACGz6fRnDk25ebs0Y58R1akQ0HTromPSjJKr0wIpE4eU%2Fqvx0nk5gapqUnC59MAQADAxlBIMUBa2ZTX%2BvujA6nXtwoACkBaYLT4a5%2BUZSlz%2BysiatpRKi7dzgAhFJIjoiKyZO5K44FZHHt1i8XMkpmZlWS27b83pZiZWZqm7A%2B0Wcb8WdzZOHfFmHgUWVS9KTlvJg%2BsX8OpM6cyRmnFdChmlslffrJizzzOQ%2FNmcufimg0jiY%2BK686GuseyY31bczyecfLOSnBFJaikjFkpcOdFohNHoZ86gUHLupzy5j9XFvzynZFwfd2Cc75lWam3u6NFxgcWK2nfpgnNCQC2kilNd5zWcr17Y97iN8t3BbvGXHCGmwAAhMBQ66OTB2MD4wFAy%2FN2F%2B54rz1zAG5YaP7Poj%2FWFwGxH4RwI2VuKAXAGMOz5U%2FoYrcUdFH%2BtgAAAABJRU5ErkJggg%3D%3D"/></a>
  <a href="https://www.jetbrains.com/idea/"><img alt="intellij" src="https://img.shields.io/badge/-IntelliJ%20IDEA-F76A00?logo=intellijidea&logoColor=white"/></a>
  <a href="https://git-scm.com/"><img alt="git" src="https://img.shields.io/badge/-Git-F05032?logo=git&logoColor=white"/></a>
  <a href="https://github.com/"><img alt="github" src="https://img.shields.io/badge/-GitHub-181717?logo=github&logoColor=white"/></a>
  <a href="https://github.com/features/actions"><img alt="github-actions" src="https://img.shields.io/badge/-GitHub%20Actions-555555?logo=githubactions&logoColor=white"/></a>
</p>

## Usage

The application supports the following execution modes:
1.  **Simulation (`sim`)**: Runs the MATSim simulation and processes events **online** (in real-time) using event handlers. This avoids generating large XML event files.
2.  **Analysis (`analysis`)**: Processes existing MATSim XML event files **offline** using a high-performance streaming parser. Useful for re-analyzing past runs.
3.  **Simple Run (`simple-run`)**: Runs the MATSim simulation **without** the post-processing scoring pipeline.
4.  **Arrow to CSV (`arrow`)**: Converts Arrow IPC data files to CSV format for inspection or debugging.

### 1. Simulation Mode (`sim`)
Run the MATSim simulation and calculate scores immediately:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.15.2.jar sim \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --score data/out/final_scores.bin \
  --format ARROW
```

#### Arguments
*   `--cfg` (`-c`): Path to application YAML configuration (**Required**).
*   `--matsim-cfg` (`-mc`): Path to MATSim XML configuration (**Required**).
*   `--score` (`-s`): Path to output binary score file (**Required**).
*   `--score-records` (`-sc`): Path to output JSON file containing individual scoring metrics breakdown (**Optional**).
*   `--format` (`-f`): Output data format (case-insensitive). Options:
    *   `ARROW` (Default): Apache Arrow IPC. High-performance binary data format, best for large datasets and continuous simulation.
    *   `CSV`: Human-readable text format, easier for debugging, cross-referencing, and simple analysis.
*   `--log-file` (`-lf`): Path to custom log file (Default: `logs/app.log`).
*   `--matsim-log`/`--no-matsim-log` (`-msl`): Toggle MATSim console logging (Default: Disabled).
*   `--signature` (`-sig`): Custom worker signature for logs (Default: Hostname).
*   `--write-throughput`/`--no-write-throughput` (`-wtrpt`/`-nwtrpt`): Enable channel throughput tracking for data writing diagnostics (Default: Disabled).

### 2. Simple Simulation (`simple-run`)
Runs the MATSim simulation **without** the post-processing scoring pipeline. Useful for validating MATSim configs or generating raw data.

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.15.2.jar simple-run \
  --matsim-cfg data/config/matsim_config.xml
```

#### Arguments
*   `--matsim-cfg` (`-mc`): Path to MATSim XML configuration (**Required**).
*   `--log-file` (`-lf`): Path to custom log file (Default: `logs/app.log`).
*   `--signature` (`-sig`): Custom worker signature for logs (Default: Hostname).

### 3. Analysis Mode (`analysis`)
Process an existing `output_events.xml.gz` file:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.15.2.jar analysis \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --events data/out/output_events.xml.gz \
  --score data/out/final_scores.bin
```

#### Arguments
*   `--events` (`-e`): Path to the MATSim events XML file (**Required**).
*   `--cfg`, `--matsim-cfg`, `--score`, `--format`, `--log-file`, `--signature`, `--write-throughput`: Same as `sim` mode.
*   **Note:** The default `--format` in analysis mode is `CSV`.
*   **Note:** `--matsim-log` and `--score-records` are **not** available in analysis mode.

### 4. Arrow to CSV (`arrow`)
Convert an Arrow IPC data file to CSV:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.15.2.jar arrow \
  --file data/temp/bus_pax_records.arrow \
  --output data/temp/bus_pax_records.csv
```

#### Arguments
*   `--file` (`-f`): Path to the Arrow IPC file to convert (**Required**).
*   `--output` (`-o`): Path to the output CSV file (**Optional**. Defaults to the same path with `.csv` extension).
*   `--log-file` (`-lf`): Path to custom log file (Default: `logs/app.log`).
*   `--signature` (`-sig`): Custom worker signature for logs (Default: Hostname).

*__Note:__ The `--add-opens` flag is mandatory for Apache Arrow to work on JDK 17+.*

## Configuration
The pipeline is controlled by a YAML config file. 

### Key Sections
*   **batch_size**: Number of events to buffer before writing to disk (Performance tuning).
*   **files → data**: Defines input/output paths. Auto-resolves extensions based on `--format` (e.g., adds `.arrow` or `.csv`).
*   **scoring → params**: Scoring model parameters:
    *   `coverage_radius`: Radius (meters) for spatial service coverage calculation.
    *   `early_headway_tolerance` / `late_headway_tolerance`: Minutes of tolerance for on-time performance.
    *   `travel_time_baseline`: Baseline travel time (minutes) used in the travel time score formula.
    *   `productivity_baseline`: Baseline passengers per service hour for the productivity score formula.
*   **scoring → weights**: Adjust the relative importance of different service metrics. Weights must sum to 1.0.

## Scoring Logic
The final network-wide score is a weighted sum of **10** key criterias:

1.  **Service Coverage**: Based on spatial availability of transit.
2.  **Ridership**: Percentage of the total population that used transit.
3.  **On-Time Performance**: Percentage of bus arrivals within the early/late headway tolerance thresholds defined in the metadata.
4.  **Travel Time Score**: Performance of bus travel times against a pre-defined baseline.
5.  **Transit-Auto Time Ratio**: A comparison of average car travel times vs. bus travel times, favoring scenarios where transit is competitive.
6.  **Productivity**: Measure of resource utilization, calculated as `Total Service Hours / Total Unique Passengers`.
7.  **Bus Efficiency**: Measures the cost-effectiveness of the network (Cost per Passenger), calculated as `Total Bus Distance / Total Unique Passengers` (inverted for normalization).
8.  **Bus Effective Travel Distance**: Ratio of `Total Distance with Passengers / Total Distance` (inverted for normalization).
9.  **Transit Route Ratio**: A pre-calculated ratio representing the spatial coverage or efficiency of the transit routes relative to the network.
10. **Bus Transfer Rate**: Average number of bus-to-bus transfers per public transport trip, calculated as `Total Bus Transfers / Total PT Trips`.

## Logging
The application uses Log4j2. You can override log levels at runtime:
```bash
java -Dlog.level.app=debug -Dlog.level.matsim=info -jar ...
```

## Outputs

### Score Binary
The file specified by `--score` contains exactly **8 bytes** (a single Big-Endian Double) representing the final aggregated score.

#### Reading the Score

##### Kotlin
```kotlin
DataInputStream(FileInputStream("final_scores.bin")).use { println(it.readDouble()) }
```

##### Python
```python
import struct
with open("final_scores.bin", "rb") as f:
    print(struct.unpack(">d", f.read(8))[0])
```

### Score Records (JSON)
When `--score-records` is specified, a JSON file is generated containing each scoring metric and the final score:

```json
{
  "transit_route_ratio": 0.0,
  "service_coverage": 0.85,
  "ridership": 0.42,
  "travel_time": 0.71,
  "transit_auto_time_ratio": 0.63,
  "on_time_perf": 0.90,
  "productivity": 0.55,
  "bus_efficiency": 0.48,
  "bus_effective_travel_distance": 0.37,
  "bus_transfer_rate": 0.12,
  "final_score": 0.6231
}
```

## Docker Usage
You must pre-install the DuckDB `arrow` extension during the image build to run offline.

```dockerfile
FROM azul/zulu-openjdk:21
RUN apt-get update && apt-get install -y wget unzip \
    && wget https://github.com/duckdb/duckdb/releases/download/v1.1.2/duckdb_cli-linux-amd64.zip \
    && unzip duckdb_cli-linux-amd64.zip -d /usr/local/bin
RUN duckdb -c "INSTALL arrow FROM community; LOAD arrow;"
COPY build/libs/dist-2.15.2.jar app.jar
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
