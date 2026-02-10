# Bus MATSim Pipeline
[🇻🇳 Tiếng Việt](README_VI.md)

A Kotlin-based post-processing and scoring pipeline for MATSim bus simulation data. Uses DuckDB for high-performance data processing and calculates Level of Service (LOS) scores based on TCQSM standards using Apache Arrow for efficient data storage.

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
  -jar build/libs/dist-2.13.0.jar sim \
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
    *   `ARROW` (Default): High-performance binary format, best for large datasets and continuous simulation.
    *   `CSV`: Human-readable text format, easier for debugging, cross-referencing and simple analysis.
*   `--log-file` (`-lf`): Path to custom log file (Default: `logs/app.log`).
*   `--matsim-log`/`--no-matsim-log` (`-msl`): Toggle MATSim console logging (Default: Disabled).
*   `--signature` (`-sig`): Custom worker signature for logs (Default: Hostname).
*   `--write-throughput`/`--no-write-throughput` (`-wtrpt`/`-nwtrpt`): Enable channel throughput tracking for data writing diagnostics (Default: Disabled).

### 2. Simple Simulation (`simple-run`)
Runs the MATSim simulation **without** the post-processing scoring pipeline. Useful for validating MATSim configs or generating raw data.

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.13.0.jar simple-run \
  --matsim-cfg data/config/matsim_config.xml
```

#### Arguments
*   `--matsim-cfg` (`-mc`): Path to MATSim XML configuration (**Required**).

### 3. Analysis Mode (`analysis`)
Process an existing `output_events.xml.gz` file:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.13.0.jar analysis \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --events data/out/output_events.xml.gz \
  --score data/out/final_scores.bin
```

#### Arguments
*   `--events` (`-e`): Path to the MATSim events XML file (**Required**).
*   `--cfg`, `--matsim-cfg`, `--score`, `--format`, `--log-file`, `--write-throughput`: Same as `sim` mode.
*   **Note:** The default `--format` in analysis mode is `CSV`.

### 4. Arrow to CSV (`arrow`)
Convert an Arrow IPC data file to CSV:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.13.0.jar arrow \
  --file data/temp/bus_pax_records.arrow \
  --output data/temp/bus_pax_records.csv
```

#### Arguments
*   `--file` (`-f`): Path to the Arrow IPC file to convert (**Required**).
*   `--output` (`-o`): Path to the output CSV file (**Optional**. Defaults to the same path with `.csv` extension).

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
The final network-wide score is a weighted sum of **10** key components:

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
When `--score-records` is specified, a JSON file is generated containing each individual scoring metric and the final score:

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
COPY build/libs/dist-2.13.0.jar app.jar
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
