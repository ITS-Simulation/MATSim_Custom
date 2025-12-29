# PT-MATSim Scoring

A Level of Service (LOS) scoring module for public transit analysis based on MATSim simulation data.

## Features

- **Wait-Ride Score Calculation**: Computes transit wait-ride scores based on headway factors and perceived travel time
- **Pedestrian Environment Score**: Evaluates pedestrian environment quality using traffic volume, speed, and cross-section data
- **LOS Grading**: Assigns letter grades (A-F) based on combined transit and pedestrian scores
- **Per-Link Analysis**: Calculate LOS for individual network links
- **Per-Line Analysis**: Calculate LOS broken down by transit line

## Installation

**From PyPI:**
```bash
pip install pt-matsim-scoring
```

**From wheel file:**
```bash
pip install pt_matsim_scoring-0.1.0-py3-none-any.whl
```

**From source (development):**
```bash
pip install -e .
```

## Prerequisites

Before using the LOS calculator, you need:

1. **MATSim simulation output** - A completed MATSim run with:
   - Network file (`network.xml`)
   - Transit schedule (`transit_schedule.xml`)
   - Output events file (`output_events.xml.gz`)

2. **Data extraction and processing** - Run the extraction and processing pipeline first:

```python
from scoring import MATSimExtractor, MATSimProcessor

# Step 1: Extract data from MATSim output
extractor = MATSimExtractor(config="config.yaml")
extractor.extract_and_output()

# Step 2: Process extracted data
processor = MATSimProcessor(config="config.yaml")
processor.process_matsim_data()

# Now you can run LOS calculations
```

## Quick Start

```python
from scoring import LOSCalculator

# Initialize with config file path
calculator = LOSCalculator("config.yaml")

# Run LOS calculation per link
calculator.run_calculation()

# Run LOS calculation per transit line
calculator.run_calculation_per_line()
```

## Configuration

The module requires a YAML configuration file with the following structure:

```yaml
mode: debug  # or "prod"

matsim:
  bus_type_prefix: "bus"      # Prefix for vehicle TYPE names in transitVehicles.xml
  bus_transport_modes:        # Transport modes to filter for buses (matches any)
    - "bus"
    - "pt"

files:
  inp:
    net: "path/to/network.xml"              # MATSim network file
    transit: "path/to/transit_schedule.xml" # MATSim transit schedule
    transit_vehicles: "path/to/transitVehicles.xml"  # MATSim transit vehicles
  out:
    events: "path/to/output_events.xml.gz"  # MATSim output events (gzipped)
  data:
    # Internal data files (auto-generated)
    total_dist: "data/out/dataset/pt_trip_total_distance.csv"
    wait_time: "data/out/dataset/pt_wait_time.csv"
    veh_flow: "data/out/dataset/vehicle_flow.csv"
    load_history: "data/out/dataset/pt_bus_load_data.csv"
    avg_trip_length: "data/out/dataset/avg_trip_length.json"
  los_data:
    merged: "data/out/los/merged_los.csv"
    filtered: "data/out/los/filtered_los.csv"
    outlier: "data/out/los/outlier_los.csv"
    scores: "data/out/los/pt_los_scores.csv"
    line_scores: "data/out/los/pt_line_los_scores.csv"
  metadata:
    bus_headway: "data/metadata/bus_line_headway.csv"
    link_data: "data/metadata/link_data.csv"
    bus_vehicles: "data/metadata/bus_vehicle_ids.csv"

bus:
  seating: 30          # Seating capacity
  standing: 20         # Standing capacity
  cap_headroom: 0.5    # Standing capacity headroom factor (0.0-1.0)

scoring:
  wait_ride:
    elas: -0.4                  # Travel time elasticity
    base_travel_time: 1.5       # Base travel time (min/km)
    headway_tolerance: 1        # Headway tolerance (minutes)
    boarding_threshold: 10      # Min boardings for weighted EWT
    total_load_threshold: 50    # Min load for weighted load factor

  amenity:
    shelter: 1.3          # Shelter time benefit (minutes)
    shelter_rate: 0.25    # Proportion of stops with shelter
    bench: 0.2            # Bench time benefit (minutes)
    bench_rate: 0.15      # Proportion of stops with bench

  # Pedestrian environment (Imperial units required)
  ped_env:
    outside_lane_width: 9     # ft
    bike_lane_width: 0        # ft
    parking_lane_width: 5     # ft
    volume_threshold: 160     # veh/hr
    street_parking: 0.25      # Parking occupancy (0.0-1.0)
    sidewalk_buffer: 0.33     # ft
    buffer_coeff: 1.0
    sidewalk_width: 6         # ft
```

## Dependencies

- pandas >= 2.0.0
- PyYAML >= 6.0
- lxml >= 6.0.2

## License

MIT License
