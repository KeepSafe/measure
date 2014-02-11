;; Copyright 2014 KeepSafe Software, Inc
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;    http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

;; ## You Can't Optimize What You Don't Measure
;;
;; Measure is a library that enables Clojure programs to track critical aspects of
;; their behavior.  Using idiomatic wrappers around Coda Hale's excellent
;; [Metrics][http://metrics.codahale.com] library, one can measure statistics from
;; simple counters to exponentially-decaying histograms.

(ns measure.core
  {}
  (:import [java.net InetSocketAddress])
  (:import [java.util.concurrent TimeUnit])
  (:import [com.codahale.metrics ConsoleReporter
                                 Counter
                                 DerivativeGauge
                                 Gauge
                                 Histogram
                                 Meter
                                 Metered
                                 MetricRegistry
                                 RatioGauge
                                 RatioGauge$Ratio
                                 Sampling
                                 Timer])
  (:import [com.codahale.metrics.graphite Graphite
                                          GraphiteReporter]))

;; The core construct of measure is the registry.  To be useful, all
;; metrics are registered with a registry, which is typically a singleton.
;;
;; The registry is a collection of named metrics.  Registries are useful both
;; to group related sets of measurements, and as the vehicle through which
;; measurements are reported.

(defn registry
  "Creates and returns a new metrics registry."
  []
  (MetricRegistry.))

(defn register
  [^MetricRegistry registry name metric]
  (.register registry name metric)
  metric)

(deftype ^:private FnGauge [value-fn]
  Gauge
  (getValue [_] (value-fn)))

;; ## Gauges
;;
;; Gauges represent an instantaneous reading of a value at a point in time.
;; They are useful for values that vary with time, such as job-queue length
;; or heap size.

;; Gauges can wrap either a function that returns a value or an atom.
(defn atom?
  "Returns true if the given value is an atom."
  [maybe-atom]
  (instance? clojure.lang.Atom maybe-atom))

(defn atom-or-fn
  "If the value is an atom, returns a no-arg function that derefs it.
   If the value is a function, it is returned unmodified.
   Otherwise, an exception is thrown."
  [val]
  (cond (atom? val) (fn [] @val)
        (fn? val) val
        :else (throw (IllegalArgumentException. "Expected an atom or a function"))))

(defn gauge
  "Registeres a gauge for the given function and returns it.

   Any time the value of the gauge is requested, the given `value-fn`
   will be invoked and its value returned.  Therefore it should be
   side-effect free, though it will obviously depend on side-effects
   to provide a value that changes over time."
  [^MetricRegistry registry ^String name value]
  (let [value-fn (atom-or-fn value)
        g (FnGauge. value-fn)]
    (register registry name g)))

;; ### Ratio Gauges
;;
;; A ratio gauge is a gauge whose value is the ratio between two
;; numbers.
(defn ratio
  "Registers a ratio gauge for the given numerator and denominator fns
   and returns it.

   Any time the value of the gauge is requested, the given fns are evaluated
   and a ratio of their results is returned.  Therefore both fns should be
   free of side effects, though they will obviously depend on side-effects
   to provide values that change over time."
  [^MetricRegistry registry ^String name numerator denominator]
  (let [n-fn (atom-or-fn numerator)
        d-fn (atom-or-fn denominator)
        r (proxy [RatioGauge]
              []
            (getRatio [_] (com.codahale.metrics.RatioGauge$Ratio/of (numerator) (denominator))))]
    (register registry name r)))

;; ### Derived Gauge
;;
;; A derived gauge yields the value of a source gauge, to which a
;; transformation is applied.  
(defn derive-gauge
  "Given a gauge and a transforming function, creates a gauge whose
   value is derived from the souce value with the transform applied
   to it."
  [^MetricRegistry registry ^String name ^Gauge gauge transform-fn]
  ; TODO: is there a way (using deftype or similar) to define a superclass-ctor
  ;       call?  It would be great not to use proxy here.
  (let [g (proxy [DerivativeGauge]
                 [gauge]
            (transform [value] (transform-fn value)))]
    (register registry name g)))

;; ## Counters
;;
;; Counters are simply gauges on 64-bit integers.  They can be incremented
;; and decremented.
(defn counter
  "Creates and registers a named counter."
  [^MetricRegistry registry ^String name]
  (.counter registry name))

(defn increment
  "Increments the given counter."
  ([^Counter ctr]
     (.inc ctr))
  ([^Counter ctr n]
     (.inc ctr n)))

(defn decrement
  "Decrements the given counter."
  ([^Counter ctr]
     (.dec ctr))
  ([^Counter ctr n]
     (.dec ctr n)))

;; ## Histograms
;;
;; Histograms measure the distribution of values, enabling us to measure
;; quantiles such as mean, median, 95th percentile, etc.
;;
;; The latter (along with its cousin "99th percentile" and "99.9th percentile")
;; are of the most interest in measurements like response-time.

(defn histogram
  [^MetricRegistry registry ^String name]
  (.histogram registry name))

;; ## Meters
;;
;; Meters measure the rate at which events occur.
;;
;; A meter will record the rate of events, reporting the mean (average) rate,
;; and more usefully will record 5-, 10-, and 15-minute moving average rates.

(defn meter
  "Registers a meter with the given name, or retrieves the named meter
   if it already exists."
  [^MetricRegistry registry ^String name]
  (.meter registry name))

(defn mark
  "Marks a single occurrence in the given meter."
  ([^Meter meter]
     (.mark meter))
  ([^Meter meter count]
     (.mark meter count)))

;; ## Timers
;;
;; Timers measure the rate at which events occur, coupled with a distribution
;; of their durations.  They effectively act as a meter over a histogram.
;; Per the Metrics implementation, they measure time using Java's high-precision
;; <code>System.nanoTime()</code> to measure durations in nanoseconds.

(defn timer
  "Registers a timer with the given name, or retrieves the named timer
   if it already exists."
  [^MetricRegistry registry ^String name]
  (.timer registry name))

(defn time!
  "Times the execution of a given function."
  [^Timer t f]
  (.time t f))

(defn timed-fn
  "Wraps the given function such that each invocation is timed by the given timer."
  [^Timer t f]
  (fn [& args] (time! t #(apply f args))))

(defmacro with-timer
  "Times the given body of code with the given timer."
  [^Timer timer & forms]
  `(with-open [t# (.time ~timer)]
     ~@forms))

;; Both timers and histograms expose methods named 'update'
;; that take an integer parameter.  To keep reflection to a
;; minimum, we use a protocol.  This way `update` can accommodate
;; both metric types, and we don't sacrifice (much) performance.
(defprotocol UpdateableMetric
  (update [metric count] "Updates the given metric"))

(extend-protocol UpdateableMetric
  Histogram
  (update [hist ^Integer count] (.update hist count))

  Timer
  (update [t millis]
    (.update t millis TimeUnit/MILLISECONDS)))

;; The five metric types each expose a scalar value, which depending on the specific
;; type of metric is more or less useful.  Gauges and counters are of course simply
;; measures of one scalar value, and so their value is the entirety of their information.
;;
;; Histograms, meters, and timers expose a raw count of events, but their value lies in
;; their snapshots.
(defprotocol SingleValuedMetric
  (value [metric] "Gets the scalar value of the given metric"))

(extend-protocol SingleValuedMetric
  Gauge
  (value [g] (.getValue g))

  Counter
  (value [c] (.getCount c))

  Histogram
  (value [h] (.getCount h))

  Meter
  (value [m] (.getCount m))

  Timer
  (value [t] (.getCount t)))

;; Some metrics (histograms, meters, and timers) provide non-scalar information, and `value` is
;; insufficient for these types.  Meters expose rates, histograms expose distribution snapshots,
;; and timers expose both.

(defn rates
  "Gets the current rate of events for the given measurement."
  [^Metered meter-or-timer]
  {:count (.getCount meter-or-timer)
   :15-minute-rate (.getFifteenMinuteRate meter-or-timer)
   :5-minute-rate (.getFiveMinuteRate meter-or-timer)
   :1-minute-rate (.getOneMinuteRate meter-or-timer)
   :mean-rate (.getMeanRate meter-or-timer)})

(defn snapshot
  "Gets a snapshot of the current distribution for the given measurement."
  [^Sampling histogram-or-timer]
  (let [s (.getSnapshot histogram-or-timer)]
    {:size (.size s)
     :min (.getMin s)
     :max (.getMax s)
     :mean (.getMean s)
     :std-dev (.getStdDev s)
     :median (.getMedian s)
     :75th-percentile (.get75thPercentile s)
     :95th-percentile (.get95thPercentile s)
     :98th-percentile (.get98thPercentile s)
     :99th-percentile (.get99thPercentile s)
     :999th-percentile (.get999thPercentile s)}))

;; ## Reporting
;;
;; For measurements to be useful, they must first be made visible.
;; Several reporters are included that report measurements at regular
;; intervals.

(defn ^TimeUnit keyword-to-timeunit
  "Returns the TimeUnit instance identified by the given keyword.

   Supported keywords are :minutes, :seconds, :millis, and :nanos."
  [kw]
  (case kw
    :minutes TimeUnit/MINUTES
    :seconds TimeUnit/SECONDS
    :millis  TimeUnit/MILLISECONDS
    :nanos   TimeUnit/NANOSECONDS))

;; ### Console Reporting
;;
;; Measurement registries can be printed to the console.  By default
;; rates are presented as events/second and durations as milliseconds.

(defn report-to-console!
  "Begins reporting the given metrics registry to the console.

   Metrics will be printed at a fixed interval, by default once
   per minute.

   Options are:
   * :rate-unit - A time unit (:seconds, :millis, :nanos) to which to convert all rates."
  [^MetricRegistry registry & {:keys [rate-unit time-unit frequency frequency-unit]
                                :or {frequency 1
                                     frequency-unit :minutes}}]
  (let [builder (cond-> (ConsoleReporter/forRegistry registry)
                        rate-unit (.convertRatesTo (keyword-to-timeunit rate-unit))
                        time-unit (.convertDurationsTo (keyword-to-timeunit time-unit)))
        reporter (.build builder)]
    (.start reporter frequency frequency-unit)))

;; ### Graphite Reporting
;;
;; Measurements can be periodically sent to a server using the Graphite
;; plaintext protocol.

(defn report-to-graphite!
  "Begins reporting the given metrics registry to a Graphite server.

   Metrics will be sent at a fixed interval, defaulting once every
   five seconds.  Metrics are sent using the names with which they
   were registered; if given, an optional prefix is prepended to each
   name.

   Options are:
   * :port - The port on which graphite is listening; defaults to 2003
   * :prefix - A string to be prefixed to the name of each metric.
   * :rate-unit - A time unit (:seconds, :millis, :nanos) to which to conver all rates.
   * :time-unit - A time unit (:seconds, :millis, :nanos) to which to convert all durations.
   * :frequency - How much time should pass between submissions; defaults to 1.
   * :frequency-unit - A time unit (:seconds, :millis, :nanos) describing the unit of :frequency; defaults to :seconds."
  [^MetricRegistry registry ^String host & options]
  (let [{:keys [port prefix rate-unit time-unit frequency frequency-unit]
         :or {port 2003
              frequency 5
              frequency-unit :seconds}} options
        socket-addr (InetSocketAddress. host (int port))
        graphite (Graphite. socket-addr)
        builder (cond-> (GraphiteReporter/forRegistry registry)
                        prefix (.prefixedWith prefix)
                        rate-unit (.convertRatesTo (keyword-to-timeunit rate-unit))
                        time-unit (.convertDurationsTo (keyword-to-timeunit time-unit)))
        reporter (.build builder graphite)]
    (.start reporter frequency (keyword-to-timeunit frequency-unit))
    reporter))
