[![Build and Release](https://github.com/ITS-Simulation/MATSim-Bus-Optimizer/actions/workflows/release.yml/badge.svg)](https://github.com/ITS-Simulation/MATSim-Bus-Optimizer/actions/workflows/release.yml)
[![Version](https://img.shields.io/badge/Release-v2.15.5-blue?logo=github)](https://github.com/ITS-Simulation/MATSim-Bus-Optimizer/releases/tag/v2.15.5)

# Bộ Tối ưu Lộ trình Xe buýt sử dụng MATSim
[🇬🇧 English](README.md)

Một pipeline xử lý và tính điểm hiệu năng xe buýt sử dụng Kotlin, tích hợp trực tiếp với mô phỏng MATSim. Ứng dụng sử dụng Apache Arrow để tối ưu lưu trữ, DuckDB cho xử lý dữ liệu quy mô lớn, và bộ chỉ số KPI theo tiêu chuẩn TCRP.

## Tính Năng

- **Đa Chế Độ Vận Hành** — Hỗ trợ chạy mô phỏng (`sim`), phân tích offline (`analysis`), chạy cơ bản (`simple-run`), và chuyển đổi dữ liệu (`arrow`).
- **Xử Lý Thời Gian Thực** — Thu thập dữ liệu trực tiếp từ luồng sự kiện của MATSim, loại bỏ hoàn toàn nhu cầu ghi/đọc file XML khổng lồ.
- **Phân Tích Offline** — Khả năng xử lý các file sự kiện MATSim (XML/GZ) cũ với tốc độ cực cao.
- **Hệ Thống Chấm Điểm TCRP** — Đánh giá lộ trình dựa trên 10 thành phần trọng số (lượng khách, độ đúng giờ, vận tốc thương mại, v.v.).
- **Đầu Ra Kép** — Hỗ trợ song song Apache Arrow IPC (dữ liệu nhị phân, hiệu suất cao) và CSV (truy xuất thủ công).
- **Tích Hợp DuckDB** — Truy vấn SQL trực tiếp trên dữ liệu mô phỏng ngay trong pipeline.
- **Cấu Hình Linh Hoạt** — Toàn bộ trọng số, tham số chấm điểm và đường dẫn dữ liệu được quản lý qua YAML.
- **Kết Xuất Tối Ưu** — Điểm số tổng hợp được đóng gói trong 8-byte binary (số thực 64-bit) để tích hợp hệ thống, kèm tùy chọn JSON chi tiết.
- **CI/CD Tự Động Hóa** — Workflow GitHub Actions tự động gắn thẻ (tag), build release, và đóng gói Docker container đa nền tảng.
- **I/O Bất Đồng Bộ** — Tận dụng Kotlin Coroutines để ghi dữ liệu dung lượng lớn không gây chặn (non-blocking) luồng mô phỏng chính.
- **Logging Cấu Trúc** — Hệ thống Log4j2 tùy biến sâu, hỗ trợ định danh worker và điều chỉnh mức độ log ngay khi đang chạy.

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

## Hướng Dẫn Sử Dụng

Ứng dụng cung cấp các chế độ vận hành sau:

1.  **Simulation (`sim`)**: Kích hoạt mô phỏng MATSim, đồng thời lắng nghe và xử lý sự kiện **thời gian thực**. Phương pháp này giúp loại bỏ bước trung gian (ghi file XML), tiết kiệm dung lượng đĩa và thời gian I/O.
2.  **Analysis (`analysis`)**: Chế độ phân tích **ngoại tuyến** dành cho các file sự kiện MATSim (XML/GZ) đã có sẵn. Sử dụng cơ chế streaming hiệu năng cao để tính toán lại điểm số mà không cần chạy lại mô phỏng.
3.  **Simple Run (`simple-run`)**: Chạy MATSim thuần túy, **bỏ qua** toàn bộ pipeline tính điểm. Dùng để kiểm thử file cấu hình (`config.xml`) hoặc tạo dữ liệu thô.
4.  **Arrow to CSV (`arrow`)**: Công cụ tiện ích để trích xuất dữ liệu từ định dạng nhị phân Arrow IPC sang CSV phục vụ debug hoặc phân tích thủ công.

### 1. Chạy Mô Phỏng (`sim`)
Thực thi mô phỏng và tính toán KPI thời gian thực:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.15.5.jar sim \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --score data/out/final_scores.bin \
  --format ARROW
```

#### Tham Số
*   `--cfg` (`-c`): Đường dẫn file cấu hình YAML pipeline (**Bắt buộc**).
*   `--matsim-cfg` (`-mc`): Đường dẫn file cấu hình MATSim XML (**Bắt buộc**).
*   `--score` (`-s`): Đường dẫn file kết quả (dạng nhị phân `*.bin`) (**Bắt buộc**).
*   `--score-records` (`-sc`): Đường dẫn xuất chi tiết điểm số (JSON) (**Tùy chọn**).
*   `--format` (`-f`): Định dạng lưu trữ dữ liệu.
    *   `ARROW` (Mặc định): Định dạng Apache Arrow IPC. Tối ưu cho hiệu năng dọc/ghi và streaming dữ liệu lớn.
    *   `CSV`: Dữ liệu bảng biểu dạng text, thuận tiện cho việc đọc hiểu và debug.
*   `--log-file` (`-lf`): File log ứng dụng (Mặc định: `logs/app.log`).
*   `--matsim-log`/`--no-matsim-log` (`-msl`): Bật/Tắt log chi tiết từ MATSim (Mặc định: Tắt).
*   `--signature` (`-sig`): Định danh worker trong log (Mặc định: Hostname).
*   `--write-throughput`/`--no-write-throughput` (`-wtrpt`/`-nwtrpt`): Theo dõi tốc độ ghi sự kiện MATSim để chẩn đoán nghẽn cổ chai (Mặc định: Tắt).

### 2. Chạy Cơ Bản (`simple-run`)
Chạy MATSim thuần, bỏ qua bước tính điểm. Thường được dùng để tạo dữ liệu thô MATSim để phân tích riêng.

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.15.5.jar simple-run \
  --matsim-cfg data/config/matsim_config.xml
```

#### Tham Số
*   `--matsim-cfg` (`-mc`): File cấu hình MATSim (**Bắt buộc**).
*   Các cờ log như `--log-file`, `--signature` vẫn khả dụng.

### 3. Phân Tích Offline (`analysis`)
Xử lý file sự kiện (`output_events.xml.gz`) từ một lần chạy trước:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.15.5.jar analysis \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --events data/out/output_events.xml.gz \
  --score data/out/final_scores.bin
```

#### Tham Số
*   `--events` (`-e`): Đường dẫn file sự kiện cần phân tích (**Bắt buộc**).
*   Các tham số khác tương tự chế độ `sim`.
*   **Lưu ý:** Format mặc định ở chế độ này là `CSV` (để tiện theo dõi và gỡ lỗi).

### 4. Công Cụ Arrow (`arrow`)
Chuyển đổi file Arrow sang CSV:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/dist-2.15.5.jar arrow \
  --file data/temp/bus_pax_records.arrow \
  --output data/temp/bus_pax_records.csv
```

#### Tham Số
*   `--file` (`-f`): File Arrow đầu vào (**Bắt buộc**).
*   `--output` (`-o`): File CSV đầu ra (**Tùy chọn** - mặc định cùng tên file gốc).

*__Quan trọng:__ Cờ `--add-opens` là bắt buộc để thư viện Apache Arrow tương thích với JDK 17+.*

## Cấu Hình Hệ Thống
Mọi tham số vận hành được quản lý qua file YAML.

### Các Khối Chính
*   **batch_size**: Kích thước bộ đệm (số lượng event) trước khi ghi xuống đĩa. (Tinh chỉnh I/O).
*   **files → data**: Các đường dẫn đến file dữ liệu. Hệ thống tự động gán đuôi file (`.arrow` / `.csv`) dựa trên tham số `--format`.
*   **scoring → params**: Tham số kỹ thuật cho mô hình chấm điểm:
    *   `coverage_radius`: Bán kính phục vụ (mét) quanh trạm dừng.
    *   `early_headway_tolerance` / `late_headway_tolerance`: Ngưỡng sai số cho phép (phút) để tính độ đúng giờ.
    *   `travel_time_baseline` / `productivity_baseline`: Các mốc tham chiếu (baseline) để so sánh hiệu năng.
*   **scoring → weights**: Trọng số cho từng thành phần điểm. Tổng phải bằng 1.0.

## Cơ Chế Chấm Điểm (Scoring Logic)
Điểm hiệu năng tổng thể (Global Score) là tổng có trọng số của **10 chỉ số thành phần**:

1.  **Service Coverage (Độ phủ)**: Mức độ tiếp cận người dân của mạng lưới xe buýt.
2.  **Ridership (Thu hút khách)**: Tỷ trọng dân số tham gia sử dụng dịch vụ.
3.  **On-Time Performance (Đúng giờ)**: Tỷ lệ chuyến xe đến bến đúng lịch trình (trong ngưỡng dung sai).
4.  **Travel Time Score (Thời gian di chuyển)**: So sánh thời gian di chuyển thực tế với kỳ vọng lý tưởng.
5.  **Transit-Auto Time Ratio (Cạnh tranh Ô tô)**: Tỷ lệ thời gian di chuyển giữa Xe buýt và Ô tô cá nhân.
6.  **Productivity (Năng suất)**: Hiệu quả vận hành - Số hành khách phục vụ trên mỗi giờ hoạt động.
7.  **Bus Efficiency (Hiệu quả chi phí)**: Chi phí vận hành trên mỗi hành khách (được chuẩn hóa).
8.  **Bus Effective Travel Distance (Quãng đường hiệu quả)**: Tỷ lệ quãng đường di chuyển có hành khách trên tổng quãng đường vận hành.
9.  **Transit Route Ratio (Hệ số tuyến)**: Chỉ số phụ trợ đánh giá cấu trúc tuyến (được tính trước).
10. **Bus Transfer Rate (Tỷ lệ chuyển tuyến)**: Mức độ thuận tiện - số lần trung chuyển bình quân của hành khách.

## Logging
Ứng dụng sử dụng Log4j2. Có thể thiết lập mức log khi chạy:
```bash
java -Dlog.level.app=debug -Dlog.level.matsim=info -jar ...
```

## Đầu Ra (Outputs)

### Score Binary
File được chỉ định bởi `--score` chứa đúng **8 bytes** (một số thực Big-Endian 64-bit) đại diện cho điểm tổng hợp cuối cùng.

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
COPY build/libs/dist-2.15.5.jar app.jar
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
