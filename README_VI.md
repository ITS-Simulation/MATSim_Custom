# Pipeline Xử Lý Bus MATSim
[🇬🇧 English](README.md)

Một pipeline dựa trên Kotlin để xử lý hậu kỳ và tính toán điểm cho dữ liệu mô phỏng xe buýt MATSim. Sử dụng DuckDB để xử lý dữ liệu hiệu năng cao và tính toán điểm Mức độ Phục vụ (Level of Service - LOS) dựa trên tiêu chuẩn TCQSM, sử dụng Apache Arrow để lưu trữ dữ liệu hiệu quả.

## Cách Sử Dụng

Ứng dụng hỗ trợ các chế độ thực thi sau:
1.  **Simulation (`sim`)**: Chạy mô phỏng MATSim và xử lý sự kiện **trực tiếp** (online/real-time) bằng các trình xử lý sự kiện. Cách này tránh việc tạo ra các file XML sự kiện khổng lồ.
2.  **Analysis (`analysis`)**: Xử lý các file sự kiện MATSim XML đã có sẵn **ngoại tuyến** (offline) bằng trình phân tích streaming hiệu năng cao. Hữu ích khi phân tích lại các lần chạy trước đó.
3.  **Simple Run (`simple-run`)**: Chạy mô phỏng MATSim **mà không** có pipeline tính điểm hậu kỳ.
4.  **Arrow to CSV (`arrow`)**: Chuyển đổi file dữ liệu Arrow IPC sang định dạng CSV để kiểm tra hoặc debug.

### 1. Chế độ Simulation (`sim`)
Chạy mô phỏng MATSim và tính toán điểm ngay lập tức:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.13.0.jar sim \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --score data/out/final_scores.bin \
  --format ARROW
```

#### Tham Số
*   `--cfg` (`-c`): Đường dẫn đến file cấu hình YAML của ứng dụng (**Bắt buộc**).
*   `--matsim-cfg` (`-mc`): Đường dẫn đến file cấu hình MATSim XML (**Bắt buộc**).
*   `--score` (`-s`): Đường dẫn file kết quả dạng binary (**Bắt buộc**).
*   `--score-records` (`-sc`): Đường dẫn file JSON chứa chi tiết từng chỉ số tính điểm (**Tùy chọn**).
*   `--format` (`-f`): Định dạng dữ liệu đầu ra (không phân biệt hoa thường). Tùy chọn:
    *   `ARROW` (Mặc định): Định dạng nhị phân hiệu năng cao, tốt nhất cho lưu trữ dữ liệu lớn và môi trường mô phỏng liên tục.
    *   `CSV`: Định dạng văn bản dễ đọc, dễ dàng debug, đối chiếu dữ liệu và phân tích dữ liệu đơn giản.
*   `--log-file` (`-lf`): Đường dẫn file log tùy chỉnh (Mặc định: `logs/app.log`).
*   `--matsim-log`/`--no-matsim-log` (`-msl`): Bật/Tắt logging chi tiết của MATSim (Mặc định: Tắt).
*   `--signature` (`-sig`): Chữ ký tùy chỉnh cho log (Mặc định: Hostname).
*   `--write-throughput`/`--no-write-throughput` (`-wtrpt`/`-nwtrpt`): Bật theo dõi throughput kênh ghi dữ liệu để chẩn đoán hiệu năng (Mặc định: Tắt).

### 2. Chế độ Chạy Đơn Giản (`simple-run`)
Chạy mô phỏng MATSim **mà không** có pipeline tính điểm hậu kỳ. Hữu ích để kiểm tra cấu hình MATSim hoặc tạo dữ liệu thô.

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.13.0.jar simple-run \
  --matsim-cfg data/config/matsim_config.xml
```

#### Tham Số
*   `--matsim-cfg` (`-mc`): Đường dẫn đến file cấu hình MATSim XML (**Bắt buộc**).

### 3. Chế độ Analysis (`analysis`)
Xử lý file `output_events.xml.gz` đã tồn tại:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.13.0.jar analysis \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --events data/out/output_events.xml.gz \
  --score data/out/final_scores.bin
```

#### Tham Số
*   `--events` (`-e`): Đường dẫn đến file sự kiện MATSim XML (**Bắt buộc**).
*   `--cfg`, `--matsim-cfg`, `--score`, `--format`, `--log-file`, `--write-throughput`: Tương tự chế độ `sim`.
*   **Lưu ý:** Giá trị mặc định của `--format` trong chế độ analysis là `CSV`.

### 4. Chuyển Đổi Arrow sang CSV (`arrow`)
Chuyển đổi file dữ liệu Arrow IPC sang CSV:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.13.0.jar arrow \
  --file data/temp/bus_pax_records.arrow \
  --output data/temp/bus_pax_records.csv
```

#### Tham Số
*   `--file` (`-f`): Đường dẫn đến file Arrow IPC cần chuyển đổi (**Bắt buộc**).
*   `--output` (`-o`): Đường dẫn file CSV đầu ra (**Tùy chọn**. Mặc định sử dụng cùng đường dẫn với đuôi `.csv`).

*__Lưu ý:__ Cờ `--add-opens` là bắt buộc để Apache Arrow hoạt động trên JDK 17+.*

## Cấu Hình
Pipeline được điều khiển bởi một file cấu hình YAML. 

### Các Phần Chính
*   **batch_size**: Số lượng sự kiện cần buffer trước khi ghi xuống đĩa (Tinh chỉnh hiệu năng).
*   **files → data**: Định nghĩa đường dẫn input/output. Tự động thêm đuôi file dựa trên `--format` (ví dụ: thêm `.arrow` hoặc `.csv`).
*   **scoring → params**: Các tham số mô hình tính điểm:
    *   `coverage_radius`: Bán kính (mét) để tính toán độ phủ dịch vụ không gian.
    *   `early_headway_tolerance` / `late_headway_tolerance`: Số phút dung sai cho hiệu suất đúng giờ.
    *   `travel_time_baseline`: Thời gian di chuyển cơ sở (phút) dùng trong công thức tính điểm thời gian di chuyển.
    *   `productivity_baseline`: Số hành khách cơ sở trên mỗi giờ phục vụ cho công thức tính điểm năng suất.
*   **scoring → weights**: Điều chỉnh tầm quan trọng tương đối của các chỉ số dịch vụ khác nhau. Tổng trọng số phải bằng 1.0.

## Logic Tính Điểm
Điểm tổng hợp toàn hệ thống là tổng có trọng số của **10** thành phần chính:

1.  **Service Coverage (Độ phủ dịch vụ)**: Dựa trên khả năng tiếp cận không gian của phương tiện công cộng.
2.  **Ridership (Lượng hành khách)**: Tỷ lệ phần trăm tổng dân số sử dụng phương tiện công cộng.
3.  **On-Time Performance (Hiệu suất đúng giờ)**: Tỷ lệ phần trăm xe buýt đến trong ngưỡng dung sai (sớm/muộn) được định nghĩa trong metadata.
4.  **Travel Time Score (Điểm thời gian di chuyển)**: Hiệu suất thời gian di chuyển của xe buýt so với mốc cơ sở đã định trước.
5.  **Transit-Auto Time Ratio (Tỷ lệ thời gian Xe buýt - Ô tô)**: So sánh thời gian di chuyển trung bình của ô tô và xe buýt, ưu tiên các kịch bản mà phương tiện công cộng có tính cạnh tranh.
6.  **Productivity (Năng suất)**: Đo lường mức độ sử dụng tài nguyên, được tính bằng `Tổng giờ phục vụ / Tổng hành khách duy nhất`.
7.  **Bus Efficiency (Hiệu quả xe buýt)**: Đo lường tính hiệu quả chi phí của mạng lưới (Chi phí trên mỗi Hành khách), được tính bằng `Tổng quãng đường xe buýt / Tổng hành khách duy nhất` (đảo ngược để chuẩn hóa).
8.  **Bus Effective Travel Distance (Quãng đường di chuyển hiệu quả)**: Tỷ lệ `Tổng quãng đường có khách / Tổng quãng đường` (đảo ngược để chuẩn hóa).
9.  **Transit Route Ratio (Tỷ lệ tuyến vận tải)**: Tỷ lệ được tính toán trước thể hiện độ phủ không gian hoặc hiệu quả của các tuyến vận tải so với mạng lưới.
10. **Bus Transfer Rate (Tỷ lệ trung chuyển xe buýt)**: Số lượng trung chuyển xe buýt trung bình trên mỗi chuyến đi bằng phương tiện công cộng, được tính bằng `Tổng số lần trung chuyển / Tổng số chuyến đi PT`.

## Logging
Ứng dụng sử dụng Log4j2. Bạn có thể ghi đè mức log khi chạy:
```bash
java -Dlog.level.app=debug -Dlog.level.matsim=info -jar ...
```

## Đầu Ra (Outputs)

### Score Binary
File được chỉ định bởi `--score` chứa đúng **8 bytes** (một số thực Big-Endian Double) đại diện cho điểm tổng hợp cuối cùng.

#### Đọc Kết Quả

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
Khi sử dụng `--score-records`, một file JSON sẽ được tạo chứa từng chỉ số tính điểm riêng lẻ và điểm tổng hợp cuối cùng:

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

## Sử Dụng Docker
Bạn phải cài đặt sẵn extension `arrow` của DuckDB trong quá trình build image để có thể chạy offline.

```dockerfile
FROM azul/zulu-openjdk:21
RUN apt-get update && apt-get install -y wget unzip \
    && wget https://github.com/duckdb/duckdb/releases/download/v1.1.2/duckdb_cli-linux-amd64.zip \
    && unzip duckdb_cli-linux-amd64.zip -d /usr/local/bin
RUN duckdb -c "INSTALL arrow FROM community; LOAD arrow;"
COPY build/libs/dist-2.13.0.jar app.jar
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
