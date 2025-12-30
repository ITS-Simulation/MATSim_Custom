# Bus MATSim Pipeline

A Kotlin-based post-processing and scoring pipeline for MATSim bus simulation data. Uses DuckDB for high-performance data processing and calculates Level of Service (LOS) scores based on TCQSM standards.

## Usage

### Download
You can download the latest pre-built JAR from the [GitHub Releases](https://github.com/ITS-Simulation/MATSim_Custom/releases) page.

### Build
Alternatively, build the shadow jar (fat jar):
```bash
./gradlew shadowJar
```

Run the application:
```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/Bus_MATSim-1.0-SNAPSHOT.jar \
  --cfg data/config/config_v2_kotlin.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --out data/out/final_scores.bin
```

*__Note:__ These are just examples. You can modify the paths and parameters as needed.*

### Command Line Arguments
*   `--cfg`: Path to application YAML config (**Required**).
*   `--matsim-cfg`: Path to MATSim config (**Required**).
*   `--out`: Path to output binary file (**Required**).
*   `--agg`: Aggregation strategy. Values: `passenger_time`, `passenger_trip`, `operator_veh_time`, `operator_load` (Default: `passenger_time`).
*   `--log-file`: Path to custom log file (Default: `logs/app.log`).
*   `--matsim-log`: Enable MATSim logging (Default: `false`).
*   `--signature`: Custom worker signature for logs (Default: Hostname).

## Configuration
The pipeline is controlled by a YAML config file (default: `config.yaml`).

### Key Sections
*   **files -> data**: Input/Output paths for Arrow/CSV files.
*   **scoring**: Parameters for LOS calculation (weights, thresholds, penalty factors).
    *   **wait_ride**: Elasticity and base travel time.
    *   **amenity**: Shelter/bench availability.
    *   **ped_env**: Physical street attributes (lane widths, buffers) for Pedestrian Score.

## Logging
The application uses Log4j2 with dynamic configuration. Default levels are:
*   **Root**: `WARN` (Suppress noisy libs)
*   **App**: `INFO` (Show processing steps)
*   **MATSim**: `ERROR` (Suppress all MATSim warnings involved in reading events)

### Overriding Log Levels
You can change log levels at runtime using JVM system properties:

```bash
# Debug application logic, see MATSim info
java -Dlog.level.app=debug -Dlog.level.matsim=info -jar ...
```
*   `-Dlog.level.root=...`
*   `-Dlog.level.app=...`
*   `-Dlog.level.matsim=...`

## Outputs
1.  **Intermediate**: `data/temp/merged_los.arrow` (Processed raw metrics)
2.  **Final**: The file specified by `--out` (Binary format), containing a **Single Big-Endian Double** representing the aggregated network-wide score.

### Optimization Criteria Recommendation

| Optimization Goal         | Best Criterion       | Why?                                                                         |
|:--------------------------|:---------------------|:-----------------------------------------------------------------------------|
| **Max User Satisfaction** | **`PASSENGER_TIME`** | Optimizes for time-weighted happiness. Penalizes being stuck (wasting life). |
| **Max Throughput**        | `PASSENGER_TRIP`     | Optimizes for moving people far distances. Ignores congestion delays.        |
| **Min Operating Cost**    | `OPERATOR_VEH_TIME`  | Optimizes for speed/flow of buses. Ignores whether anyone is on board.       |
| **Max Asset Usage**       | `OPERATOR_LOAD`      | Optimizes for deploying big buses on short, fast links. (Abstract).          |

### Reading the Binary Output
The output file contains exactly 8 bytes (1 * 8-byte double).

#### Java / Kotlin
```kotlin
import java.io.DataInputStream
import java.io.FileInputStream

DataInputStream(FileInputStream("final_scores.bin")).use {
    val score = it.readDouble()
    println("System-wide LOS Score: $score")
}
```

#### Python
```python
import struct

with open("final_scores.bin", "rb") as f:
    # '>' forces Big-Endian (Java format), 'd' reads 1 double
    score = struct.unpack(">d", f.read(8))[0]
    
print(f"System-wide LOS Score: {score}")
```

## Docker Usage
To run this in Docker (Linux), you must **pre-install the DuckDB extensions** during the build phase. Otherwise, the application will fail if the runtime container has no internet access.

### Dockerfile Example
```dockerfile
FROM azul/zulu-openjdk:21

# 1. Install DuckDB CLI to download extensions
RUN apt-get update && apt-get install -y wget unzip \
    && wget https://github.com/duckdb/duckdb/releases/download/v1.1.2/duckdb_cli-linux-amd64.zip \
    && unzip duckdb_cli-linux-amd64.zip -d /usr/local/bin \
    && rm duckdb_cli-linux-amd64.zip

# 2. Pre-download 'arrow' extension to /root/.duckdb/extensions/...
# This runs once at build time so runtime doesn't need internet
RUN duckdb -c "INSTALL arrow; LOAD arrow;"

# 3. Copy Application
COPY build/libs/Bus_MATSim-1.0-SNAPSHOT.jar app.jar

# 4. Run
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
