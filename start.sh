#!/bin/bash
> logs/input1.log
> logs/input2.log
> logs/caudal.log
#lein jar
#java -cp target/caudal-0.1.3.jar:lib/caudal-0.1.3-standalone.jar mx.interware.caudal.core.Starter config/caudal-config.edn
lein run config/caudal-config.edn
