import pandas as pd
from scoring.utility import dataframe_to_csv

def process_los_scores():
    los_scores = pd.read_csv("data_out/los_scores.csv", sep=",", header=0, index_col=0)

    filtered_los_scores = los_scores.loc[los_scores["los"] <= 2]

    dataframe_to_csv(filtered_los_scores, "data_out/filtered_los_scores.csv", index_label="link_id")

if __name__ == "__main__":
    process_los_scores()