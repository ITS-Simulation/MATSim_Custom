import time

from scoring.MATSimExtractorV2 import MATSimExtractorV2
from scoring.MATSimProcessor import MATSimProcessor
from scoring.MATSimExtractor import MATSimExtractor
from scoring.los import LOSCalculator


def run(config: str) -> None:
    total_start = time.perf_counter()

    # Step 1: Extract data from MATSim output
    print("Starting data extraction...")
    start = time.perf_counter()
    ext = MATSimExtractorV2(config=config)
    ext.extract_and_output()
    print(f"Data extraction completed in {time.perf_counter() - start:.2f}s")

    # # Step 2: Process extracted data
    # print("Starting data processing...")
    # start = time.perf_counter()
    # processor = MATSimProcessor(config=config)
    # processor.process_matsim_data()
    # print(f"Data processing completed in {time.perf_counter() - start:.2f}s")
    #
    # # Step 3: Calculate LOS scores
    # print("Starting LOS calculation...")
    # start = time.perf_counter()
    # los = LOSCalculator(config=config)
    # los.run_calculation()
    # los.run_calculation_per_line()
    # print(f"LOS calculation completed in {time.perf_counter() - start:.2f}s")
    #
    # print("Getting LOS aggregated results...")
    # start = time.perf_counter()
    # print(los.aggregate_los_score("operator_load"))
    # print(los.aggregate_los_score("operator_veh_time"))
    # print(los.aggregate_los_score("passenger_trip"))
    # print(los.aggregate_los_score("passenger_time"))
    # print(f"LOS aggregation completed in {time.perf_counter() - start:.2f}s")
    #
    # print(f"\nTotal pipeline duration: {time.perf_counter() - total_start:.2f}s")


if __name__ == "__main__":
    yaml = "data/config/config.yaml"
    run(config=yaml)