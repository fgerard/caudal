# Caudal
---

Caudal is a data analysis platform that receives application events. Using a powerful DSL, brings you a sophisticated way to enrich and correlate all incoming events, in order to get rich information for reliability monitoring.

![Caudal Basic Diagram](https://fgerard.github.io/caudal.docs/docs/diagram-basic.svg)

## Listeners
They are a mechanism to put data in Caudal. Anything can be a datasource: application logs, a Telegram chat, or a Twitter feed.
## Parsers
Functions than receive as input some data and returns structured events. 

## Events
All data in Caudal are received in form of events. An event are any data struct and is passed as a Clojure Map.

## Streamers
A streamer is function applied  for each incoming event and can be composed and combinated to enrich the data stream.

## State
Caudal uses an application State in order to store data needed for metrics, statistics, configurations, etcetera

## Configuration
The following file shows a basic configuration that captures data from TCP 9900 port, encapsulates this data in a map, then passes it to two streamers, the first one counts event and the second prints the enriched event in log.

```clojure config/basic-config.clj
;; Requires
(ns caudal.config.basic
  (:require
   [mx.interware.caudal.io.rest-server :refer :all]
   [mx.interware.caudal.streams.common :refer :all]
   [mx.interware.caudal.streams.stateful :refer :all]
   [mx.interware.caudal.streams.stateless :refer :all]))

;; Parser
(defn basic-parser[incoming-data]
  {:message incoming-data})

;; Listener
(deflistener tcp [{:type 'mx.interware.caudal.io.tcp-server
                   :parameters {:parser basic-parser
                                :port 9900
                                :idle-period 60}}])

(defsink example 1 ;; backpressure
  ;; streamer
  (counter [:state-counter :event-counter]
           ; streamer
           (->INFO [:all])))

;; Wires our listener with the streamers
(wire [tcp] [example])
```

Running this configuration:
[![asciicast](https://asciinema.org/a/205955.png)](https://asciinema.org/a/205955)

