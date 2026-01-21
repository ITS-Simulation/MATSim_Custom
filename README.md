# Bus MATSim Pipeline
[🇻🇳 Tiếng Việt](README_VI.md)

A Kotlin-based post-processing and scoring pipeline for MATSim bus simulation data. Uses DuckDB for high-performance data processing and calculates Level of Service (LOS) scores based on TCQSM standards using Apache Arrow for efficient data storage.

## Usage

### Download
You can download the latest pre-built JAR and the default `config.yaml` from the [GitHub Releases](https://github.com/ITS-Simulation/MATSim_Custom/releases) page.

### Build
Alternatively, build the shadow jar (fat jar):
```bash
./gradlew shadowJar
```

Run the application:
```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.0.0.jar \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --out data/out/final_scores.bin
```

*__Note:__ Replace `2.0.0` with the actual version you are using. The `--add-opens` flag is mandatory for Apache Arrow to work on JDK 17+.*

### Command Line Arguments
*   `--cfg`: Path to application YAML configuration (**Required**).
*   `--matsim-cfg`: Path to MATSim XML configuration (**Required**).
*   `--out`: Path to output binary file (**Required**).
*   `--log-file`: Path to custom log file (Default: `logs/app.log`).
*   `--matsim-log`: Enable detailed MATSim console logging (Default: `false`).
*   `--signature`: Custom worker signature for logs (Default: Hostname).

## Configuration
The pipeline is controlled by a YAML config file. 

### Key Sections
*   **files -> data**: Defines input/output paths for the generated Arrow records.
*   **scoring -> weights**: Adjust the relative importance of different service metrics.

## Scoring Logic
The final network-wide score is a weighted sum of seven key components:

1.  **Service Coverage**: Based on spatial availability of transit.
2.  **Ridership**: Percentage of the total population that used transit.
3.  **On-Time Performance**: Percentage of bus arrivals within the early/late headway tolerance thresholds defined in the metadata.
4.  **Travel Time Score**: Performance of bus travel times against a pre-defined baseline.
5.  **Transit-Auto Time Ratio**: A comparison of average car travel times vs. bus travel times, favoring scenarios where transit is competitive.
6.  **Bus Efficiency**: Measures the cost-effectiveness of the network (Cost per Passenger), calculated as Total Unique Passengers / Total Bus Distance (inverted for normalization).
7.  **Bus Effective Travel Distance**: Ratio of total distance vs total distance with passengers (inverted for normalization).

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
COPY build/libs/dist-2.0.0.jar app.jar
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
