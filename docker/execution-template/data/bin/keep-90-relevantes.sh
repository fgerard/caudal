#!/bin/bash
ls -1t /opt/quantum/event-stream/data/relevantes | egrep "^relevantes.*\.edn\.txt$" | awk 'NR > 90' | xargs -I{} rm -v /opt/quantum/event-stream/data/relevantes/{} >> /opt/quantum/event-stream/data/logs/keep-90.log 2>&1
