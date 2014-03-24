# Measure Everything

Measure is a library that enables Clojure programs to track all aspects of their performance.  Using idiomatic wrappers around Coda Hale's excellent [Metrics][0] library, developers can measure things from simple values to weighted histograms over time.

You can't optimize what you don't measure; `measure` makes it easy to know what needs optimizing.

[![Build Status](https://travis-ci.org/KeepSafe/measure.png?branch=master)](https://travis-ci.org/KeepSafe/measure)

## Usage

Add this to your Leiningen project's dependencies:
```clojure
[measure "0.1.6-SNAPSHOT"]
```

Then, measure all the things:

```clojure
(require '[measure.core :refer [registry meter timer mark update with-timer]])

;; All metrics live in a registry; you'll want to have one of these somewhere.
(def metrics (registry))

;; Meters track the rate of occurrences of a thing over time
(def request-meter
  (meter metrics "api.request-rates"))

;; It's simple to add measurements to e.g. Ring middleware
(defn with-metering
  [handler]
  (fn [request]
    (mark request-meter)
    (handler request)))

;; Timers measure the rate at which events occur as well as the distribution
;; of their durations.
(def api-timer
  (timer metrics "api.request-times")

(defn wrap-with-timer
  [handler]
  (fn [request]
    (with-timer api-timer
      (handler request))))
```

## Measurements

Measurements come in several different kinds - Gauge, Counter, Meter, Histogram, and Timer.

### Gauge

A gauge is a simple instantaneous reading of a stateful value, such as current heap size.

```clojure
(def some-state (atom 0))

(def state-gauge (gauge metrics "state.atom" some-state))
```

A gauge can also be created with an arbitrary function:

```clojure
(def fn-gauge (gauge metrics "not.very.useful" #(rand)))
```

The value of a gauge can be obtained with the standard `value` function:

```clojure
(value my-gauge) ; => the gauge's current value
```

#### Ratio Gauge

A ratio gauge is a gauge that measures the ratio of two numbers.  These numbers are provided by numerator and denominator atoms-or-fns.

```clojure
(def cache-hits (counter metrics "cache.hits"))
(def cache-accesses (counter metrics "cache.accesses"))

(def hit-ratio (ratio metrics "cache.ratio" #(value cache-hits) #(value cache-accesses)))

```

#### Derivative Gauge

A derived gauge is a gauge on the value of another gauge, with a transformation function applied.

```clojure
(def memcache-connections-gauge (gauge metrics "memcache.connections" connections-atom))

(def memcache-conn-count-gauge
  (derive-gauge metrics memcache-connections-gauge
                "memcache.connections.count" #(count %)))
```

### Counter

A counter is a gauge over an integer, and can be incremented or decremented.

```clojure
(def queue-length (counter metrics "queue.length"))

(defn enqueue
  [q item]
  (increment queue-length)
  ...)

(defn dequeue
  [q]
  (decrement queue-length)
  ...)
```

Counter values can be obtained with the standard `value` function:

```clojure
(value my-counter) ; => the counter's current value
```

### Histogram

A histogram measures the distribution of numerical events over time.  They can identify not only the mean and median of a set, but also quantiles e.g. the 95th percentile.

```clojure
(def response-sizes (histogram metrics "response.size"))

(defn measuring-response-sizes
  [handler]
  (fn [request]
    (let [resp (handler request)]
      (update response-sizes (sizeof resp))
      resp)))
```

To expose the sampled distribution recorded, histograms support the `snapshot` function as well as the standard `value` function:

```clojure
(value my-histogram) ; => the number of events sampled
(snapshot my-histogram) ; => { :mean n
                        ;      :median n
                        ;      :std-dev n
                        ;      :size n
                        ;      :75th-percentile n
                        ;      :95th-percentile n
                        ;      :98th-percentile n
                        ;      :99th-percentile n
                        ;      :999th-percentile n }
```

### Meter

A meter measures the rate at which events occur.

```clojure
(def successful-authorization (meter metrics "auth.successful"))

(defn authorize!
  [request]
  ...
  (when auth-successful?
    (mark successful-authorization)))
```

To expose the rates of events, meters support the `rates` function as well as the standard `value` function:

```clojure
(value my-meter) ; => the number of events marked
(rates my-meter) ; =>  { :count n
                 ;       :mean-rate n
                 ;       :1-minute-rate n
                 ;       :5-minute-rate n
                 ;       :15-minute-rate n }
```

### Timer

A timer acts as a combination of meter and histogram, marking the rate of events as well as the distribution of their durations.

```clojure
(def request-times (timer metrics "request.duration"))

(defn measuring-response-times
  [handler]
  (fn [request]
    (with-timer request-times
      (handler request))))

;; Also, you can time functions directly
(time! #(nth-prime 1000000))

;; or, wrap an existing function in a timer so it gets measured every time it is invoked
(defn nth-prime
  [n]
  ...)

(def nth-prime (timed-fn nth-prime))
```

As both a meter and a histogram, timers support the `value`, `rate`, and `snapshot` functions:

```clojure
(value my-timer) ; => the number of events recorded

(rates my-timer) ; => the 1-, 5-, and 15-minute rates of the events recorded

(snapshot my-timer) ; => a snapshot of the distribution of event times

```

## Reporting Measurements

For measurements to be useful, they need to be visible.  Measurements reflect only the current moment in time, so to be useful they must be recorded somewhere.  Measurements can be reported in a number of ways for human consumption; currently, `measure` provides for reporting to the console and to a Graphite server.

### Console Reporting

For measuring during development, measurements can be printed to the console.

```clojure
;; This will print the current value of the registry to the console every 5 seconds
(def reporter (console-reporter metrics :frequency 5 :frequency-unit :seconds))

(start! reporter)
```

### Graphite Reporting

In production, the console is not a practical place to send measurements.  A more useful destination is a Graphite server.

Installing and operating a Graphite server can be tricky, but feeding your measurements to it is simple.

```clojure
(def reporter
  (graphite-reporter metrics
                     :host "graphite.mydomain.com"
                     :port 2003                      ;; :port defaults to 2003
                     :frequency 5                    ;; :frequency defaults to 5
                     :frequency-unit :seconds)       ;; :frequency-unit defaults to :seconds

(start! reporter)
```

### Stop Reporting

Measurement reporters all implement `java.io.Closeable`, and so can be closed in the usual ways.  A method `stop!` is also provided:

```clojure

(with-open [r (console-reporter ...)]
  ;; code code code
  )

(stop! my-graphite-reporter)
```

## Ring Measurements

One of the most prominent uses of Clojure is Ring web applications.  Measure includes middleware to take common measurements, including rates of HTTP request methods and status codes and a distribution of request body sizes.

Ring measurements include:

- `requests.gets`
- `requests.posts`
- `requests.puts`
- `requests.deletes`
- `requests.heads`
- `requests.options`
- `responses.1XX`
- `responses.2XX`
- `responses.3XX`
- `responses.4XX`
- `responses.5XX`

```clojure
(use 'measure.ring)

(defn app
  [registry]
  (-> (define-routes)
      (other-middleware)
      (with-measurements registry)))
```

## License

Copyright © 2014 KeepSafe Software, Inc

Distributed under the Apache License, Version 2.0.

[0]: http://metrics.codahale.com/
