#!/bin/bash

cat  logs/ai-streamer.log | egrep "send-events.*camera.*FACE"  | awk -F "[ ,]" '{print($2,$19,$31)}' | awk 'BEGIN {}; {if ($2 != id[$3])  print($0); id[$3]=$2;}'
