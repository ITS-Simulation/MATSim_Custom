# Pipeline Xử Lý Bus MATSim
[🇬🇧 English](README.md)

Một pipeline dựa trên Kotlin để xử lý hậu kỳ và tính toán điểm cho dữ liệu mô phỏng xe buýt MATSim. Sử dụng DuckDB để xử lý dữ liệu hiệu năng cao và tính toán điểm Mức độ Phục vụ (Level of Service - LOS) dựa trên tiêu chuẩn TCQSM, sử dụng Apache Arrow để lưu trữ dữ liệu hiệu quả.

## Cách Sử Dụng

### Tải Xuống
Bạn có thể tải file JAR đã build sẵn và file `config.yaml` mặc định từ trang [GitHub Releases](https://github.com/ITS-Simulation/MATSim_Custom/releases).

### Build
Hoặc tự build file shadow jar (fat jar):
```bash
./gradlew shadowJar
```

Chạy ứng dụng:
```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.0.0.jar \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --out data/out/final_scores.bin
```

*__Lưu ý:__ Thay thế `2.0.0` bằng phiên bản thực tế bạn đang sử dụng. Cờ `--add-opens` là bắt buộc để Apache Arrow hoạt động trên JDK 17+.*

### Tham Số Dòng Lệnh
*   `--cfg`: Đường dẫn đến file cấu hình YAML của ứng dụng (**Bắt buộc**).
*   `--matsim-cfg`: Đường dẫn đến file cấu hình MATSim XML (**Bắt buộc**).
*   `--out`: Đường dẫn file kết quả dạng binary (**Bắt buộc**).
*   `--log-file`: Đường dẫn file log tùy chỉnh (Mặc định: `logs/app.log`).
*   `--matsim-log`: Bật logging chi tiết của MATSim (Mặc định: `false`).
*   `--signature`: Chữ ký tùy chỉnh cho log (Mặc định: Hostname).

## Cấu Hình
Pipeline được điều khiển bởi một file cấu hình YAML.

### Các Phần Chính
*   **files -> data**: Định nghĩa đường dẫn input/output cho các bản ghi Arrow được tạo ra.
*   **scoring -> weights**: Điều chỉnh tầm quan trọng tương đối của các chỉ số dịch vụ khác nhau.

## Logic Tính Điểm
Điểm tổng hợp toàn hệ thống là tổng có trọng số của bảy thành phần chính:

1.  **Service Coverage (Độ phủ dịch vụ)**: Dựa trên khả năng tiếp cận không gian của phương tiện công cộng.
2.  **Ridership (Lượng hành khách)**: Tỷ lệ phần trăm tổng dân số sử dụng phương tiện công cộng.
3.  **On-Time Performance (Hiệu suất đúng giờ)**: Tỷ lệ phần trăm xe buýt đến trong ngưỡng dung sai (sớm/muộn) được định nghĩa trong metadata.
4.  **Travel Time Score (Điểm thời gian di chuyển)**: Hiệu suất thời gian di chuyển của xe buýt so với mốc cơ sở đã định trước.
5.  **Transit-Auto Time Ratio (Tỷ lệ thời gian Xe buýt - Ô tô)**: So sánh thời gian di chuyển trung bình của ô tô và xe buýt, ưu tiên các kịch bản mà phương tiện công cộng có tính cạnh tranh.
6.  **Bus Efficiency (Hiệu quả xe buýt)**: Đo lường tính hiệu quả chi phí của mạng lưới (Chi phí trên mỗi Hành khách), được tính bằng Tổng số hành khách duy nhất / Tổng quãng đường xe buýt (đảo ngược để chuẩn hóa).
7.  **Bus Effective Travel Distance (Quãng đường di chuyển hiệu quả)**: Tỷ lệ tổng quãng đường di chuyển so với tổng quãng đường di chuyển có hành khách (đảo ngược để chuẩn hóa).

## Logging
Ứng dụng sử dụng Log4j2. Bạn có thể ghi đè mức log khi chạy:
```bash
java -Dlog.level.app=debug -Dlog.level.matsim=info -jar ...
```

## Đầu Ra (Outputs)
File được chỉ định bởi `--out` chứa đúng **8 bytes** (một số thực Big-Endian Double) đại diện cho điểm tổng hợp cuối cùng.

### Đọc Kết Quả

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

## Sử Dụng Docker
Bạn phải cài đặt sẵn extension `arrow` của DuckDB trong quá trình build image để có thể chạy offline.

```dockerfile
FROM azul/zulu-openjdk:21
RUN apt-get update && apt-get install -y wget unzip \
    && wget https://github.com/duckdb/duckdb/releases/download/v1.1.2/duckdb_cli-linux-amd64.zip \
    && unzip duckdb_cli-linux-amd64.zip -d /usr/local/bin
RUN duckdb -c "INSTALL arrow FROM community; LOAD arrow;"
COPY build/libs/dist-2.0.0.jar app.jar
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
