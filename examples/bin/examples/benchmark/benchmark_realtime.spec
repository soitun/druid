[{ "config" : { "intermediatePersistPeriod" : "PT1M",
      "maxRowsInMemory" : 500000
    },
  "firehose" : { "parser" : { "data" : { "columns" : [ "timestamp", 
  				"color", "random_num", "day" , "month", "date" , "time"
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
      "file" : "config/event.txt",
      "type" : "conjurer"
    },
  "plumber" : { "type" : "realtime" ,
                  "windowPeriod" : "PT1m",
                "segmentGranularity":"hour",
                "basePersistDirectory" : "/tmp/realtime/eval2BasePersist" },
  "schema" : { "aggregators" : [  
  		{ "type": "longSum", "name": "longSum", "fieldName" : "random_num" } ,
   		{ "type": "doubleSum", "name": "doubleSum", "fieldName" : "random_num" } ,
    	{ "type": "max", "name": "max", "fieldName" : "random_num" }  ,
     	{ "type": "min", "name": "min", "fieldName" : "random_num" }  
     	],
      "dataSource" : "performance",
      "indexGranularity" : "minute"
    }
}]