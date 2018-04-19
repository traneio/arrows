#!/bin/bash
sbt "project arrows-benchmark" "jmh:run -foe true -gc true -prof gc -rf csv $1"
cd arrows-benchmark/results
mv ../../jmh-result.csv .
cat jmh-result.csv | sed 's/Benchmark./Benchmark","/g' | grep ops > thrpt.csv
cat jmh-result.csv | sed 's/Benchmark./Benchmark","/g' | grep gc.alloc.rate.norm > gc.csv
echo "See arrows-benchmark/results for the result CSVs"