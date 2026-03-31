#!/bin/bash

for i in $( seq $1 $2 ); do    cat  logs/ai-streamer.log | egrep "send-events.*camera.*PLATE"  | awk -F "[ ,]" '{print($2,$19,$34)}' | awk 'BEGIN {}; {if ($2 != id[$3])  print($0); id[$3]=$2;}' | egrep "^$i" | wc -l; done
