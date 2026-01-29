# Bus MATSim Pipeline
[🇻🇳 Tiếng Việt](README_VI.md)

A Kotlin-based post-processing and scoring pipeline for MATSim bus simulation data. Uses DuckDB for high-performance data processing and calculates Level of Service (LOS) scores based on TCQSM standards using Apache Arrow for efficient data storage.

## Usage

The application supports two execution modes:
1.  **Simulation (`sim`)**: Runs the MATSim simulation and processes events **online** (in real-time) using event handlers. This avoids generating large XML event files.
2.  **Analysis (`analysis`)**: Processes existing MATSim XML event files **offline** using a high-performance streaming parser. Useful for re-analyzing past runs.

### 1. Simulation Mode (`sim`)
Run the MATSim simulation and calculate scores immediately:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.8.0.jar sim \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --out data/out/final_scores.bin \
  --format ARROW
```

#### Arguments
*   `--cfg`: Path to application YAML configuration (**Required**).
*   `--matsim-cfg`: Path to MATSim XML configuration (**Required**).
*   `--out`: Path to output binary file (**Required**).
*   `--format`: Output data format (case-insensitive). Options:
    *   `ARROW` (Default): High-performance binary format, best for large datasets and continuous simulation.
    *   `CSV`: Human-readable text format, easier for debugging, cross-referencing and simple analysis.
*   `--log-file`: Path to custom log file (Default: `logs/app.log`).
*   `--matsim-log`/`--no-matsim-log`: Toggle MATSim console logging (Default: Disabled).
*   `--signature`: Custom worker signature for logs (Default: Hostname).

### 2. Simple Simulation (`simple-run`)
Runs the MATSim simulation **without** the post-processing scoring pipeline. Useful for validating MATSim configs or generating raw data.

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.8.0.jar simple-run \
  --matsim-cfg data/config/matsim_config.xml
```

#### Arguments
*   `--matsim-cfg`: Path to MATSim XML configuration (**Required**).

### 3. Analysis Mode (`analysis`)
Process an existing `output_events.xml.gz` file:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.8.0.jar analysis \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --events data/out/output_events.xml.gz \
  --out data/out/final_scores.bin
```

#### Arguments
*   `--events`: Path to the MATSim events XML file (**Required**).
*   `--cfg`, `--matsim-cfg`, `--out`, `--format`, `--log-file`: Same as `sim` mode.

*__Note:__ The `--add-opens` flag is mandatory for Apache Arrow to work on JDK 17+.*

## Configuration
The pipeline is controlled by a YAML config file. 

### Key Sections
*   **batch_size**: Number of events to buffer before writing to disk (Performance tuning).
*   **files -> data**: Defines input/output paths. Auto-resolves extensions based on `--format` (e.g., adds `.arrow` or `.csv`).
*   **scoring -> weights**: Adjust the relative importance of different service metrics.

## Scoring Logic
The final network-wide score is a weighted sum of **ten** key components:

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
The file specified by `--out` contains exactly **8 bytes** (a single Big-Endian Double) representing the final aggregated score.

### Reading the Score

#### Kotlin
```kotlin
DataInputStream(FileInputStream("final_scores.bin")).use { println(it.readDouble()) }
```

#### Python
```python
import struct
with open("final_scores.bin", "rb") as f:
    print(struct.unpack(">d", f.read(8))[0])
```

## Docker Usage
You must pre-install the DuckDB `arrow` extension during the image build to run offline.

```dockerfile
FROM azul/zulu-openjdk:21
RUN apt-get update && apt-get install -y wget unzip \
    && wget https://github.com/duckdb/duckdb/releases/download/v1.1.2/duckdb_cli-linux-amd64.zip \
    && unzip duckdb_cli-linux-amd64.zip -d /usr/local/bin
RUN duckdb -c "INSTALL arrow FROM community; LOAD arrow;"
COPY build/libs/dist-2.8.0.jar app.jar
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
