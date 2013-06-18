[{ "config" : { "intermediatePersistPeriod" : "PT1M",
      "maxRowsInMemory" : 500000
    },
  "firehose" : { "parser" : { "data" : { "columns" : [ "timestamp", 
  				"col31"
                ],
              "delimiter" : "#",
              "dimensions" :  [ ],
              "format" : "tsv"
            },
          "dimensionExclusions" : [],
          "timestampSpec" : { "column" : "timestamp",
              "format" : "millis"
            }
        },
      "rate" : 50000,
      "file" : "examples/benchmark/event.txt",
      "type" : "conjurer"
    },
  "plumber" : { "type" : "realtime" ,
                  "windowPeriod" : "PT1m",
                "segmentGranularity":"hour",
                "basePersistDirectory" : "/tmp/realtime/eval2BasePersist" },
  "schema" : { "aggregators" : [],
      "dataSource" : "performance",
      "indexGranularity" : "minute"
    }
}]