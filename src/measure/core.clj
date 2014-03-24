;; @MargDisable
;;
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
;;
;; @MargEnable

(ns measure.core
  "## You Can't Optimize What You Don't Measure

   Measure is a library that enables Clojure programs to track critical aspects of
   their behavior.  Using idiomatic wrappers around Coda Hale's excellent
   [Metrics](http://metrics.codahale.com) library, one can measure statistics from
   simple counters to exponentially-decaying histograms."
  (:import [java.io Closeable]
           [java.net InetSocketAddress]
           [java.util.concurrent TimeUnit]
           [com.codahale.metrics ConsoleReporter
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
                                 Timer
                                 Timer$Context]
           [com.codahale.metrics.graphite Graphite
                                          GraphiteReporter]))

(defn registry
  "The core construct of measure is the registry.  To be useful, all
   metrics are registered with a registry, which is typically a singleton.

   The registry is a collection of named metrics.  Registries are useful both
   to group related sets of measurements, and as the vehicle through which
   measurements are reported."
  []
  (MetricRegistry.))

(defn- register
  [^MetricRegistry registry name metric]
  (.register registry name metric)
  metric)

(defn- atom-or-fn
  [val]
  (cond (instance? clojure.lang.Atom val) (fn [] @val)
        (fn? val) val
        :else (throw (IllegalArgumentException. "Expected an atom or a function"))))

(defn- ^TimeUnit keyword-to-timeunit
  [kw]
  (case kw
    :days    TimeUnit/DAYS
    :hours   TimeUnit/HOURS
    :minutes TimeUnit/MINUTES
    :seconds TimeUnit/SECONDS
    :millis  TimeUnit/MILLISECONDS
    :micros  TimeUnit/MICROSECONDS
    :nanos   TimeUnit/NANOSECONDS))

(defn gauge
  "## Gauges

   Gauges represent an instantaneous reading of a value at a point in time.
   They are useful for values that vary with time, such as job-queue length
   or heap size.

   Gauges can wrap either a function that returns a time-varying value or
   an atom.

   Any time the value of the gauge is requested, the given `value`
   will be invoked and its value returned.  Therefore it should be
   side-effect free, though it will obviously depend on side-effects
   to provide a value that changes over time."
  [^MetricRegistry registry ^String name value]
  (let [value-fn (atom-or-fn value)
        g (reify Gauge
            (getValue [_] (value-fn)))]
    (register registry name g)))

(defn ratio
  "### Ratio Gauge

   A ratio gauge is a gauge whose value is the ratio between two numbers.

   Like standard gauges, they get their values from either an atoms or functions.
   Unlike standard gauges, they are made from a numerator atom-or-fn and a
   denominator atom-or-fn.

   Any time the value of the gauge is requested, the given fns are evaluated
   and a ratio of their results is returned.  Therefore both fns should be
   free of side effects, though they will obviously depend on side-effects
   to provide values that change over time."
  [^MetricRegistry registry ^String name numerator denominator]
  (let [n-fn (atom-or-fn numerator)
        d-fn (atom-or-fn denominator)
        r (proxy [RatioGauge]
              []
            (getRatio [] (RatioGauge$Ratio/of (n-fn) (d-fn))))]
    (register registry name r)))

(defn derive-gauge
  "### Derived Gauge

   A derived gauge yields the value of a source gauge, to which a
   transformation is applied.  The `transform-fn` should accept
   a single value and return a non-nil result."
  [^MetricRegistry registry ^String name ^Gauge gauge transform-fn]
  ; TODO: is there a way (using deftype or similar) to define a superclass-ctor
  ;       call?  It would be great not to use proxy here.
  (let [g (proxy [DerivativeGauge]
                 [gauge]
            (transform [value] (transform-fn value)))]
    (register registry name g)))

(defn counter
  "## Counters

   Counters are simply gauges on 64-bit integers.  They can be incremented
   and decremented."
  [^MetricRegistry registry ^String name]
  (.counter registry name))

(defn increment
  ([^Counter ctr]
     (.inc ctr))
  ([^Counter ctr n]
     (.inc ctr n)))

(defn decrement
  ([^Counter ctr]
     (.dec ctr))
  ([^Counter ctr n]
     (.dec ctr n)))

(defn histogram
  "## Histograms

   Histograms measure the distribution of values, enabling us to measure
   quantiles such as mean, median, 95th percentile, etc.

   The latter (along with its cousins \"99th percentile\" and \"99.9th percentile\")
   are of the most interest in measurements like response-time."
  [^MetricRegistry registry ^String name]
  (.histogram registry name))

(defn meter
  "## Meters

   Meters measure the rate at which events occur.

   A meter will record the rate of events, reporting the mean (average) rate,
   and more usefully will record 5-, 10-, and 15-minute moving average rates.

   Meters can be updated by `mark`ing them, optionally with a count of events
   if more than one has occurred."
  [^MetricRegistry registry ^String name]
  (.meter registry name))

(defn mark
  ([^Meter meter]
     (.mark meter))
  ([^Meter meter count]
     (.mark meter count)))

(defn timer
  "## Timers

   Timers measure the rate at which events occur, coupled with a distribution
   of their durations.  They effectively act as a meter over a histogram.
   Per the Metrics implementation, they measure time using Java's high-precision
   <code>System.nanoTime()</code> to measure durations in nanoseconds."
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

(defn start-timing
  "Operations occasionally cross thread boundaries, and even more frequently
   cross lexical scopes; the `start-timing` function is useful in these cases.
   It returns a handle that implements <code>java.io.Closeable</code> which,
   when closed (either with <code>(.close handle)</code> or the companion
   `stop-timing` function) will complete the timing.  Just like your stopwatch.

   Begins timing and returns a handle with which to stop timing.  Stop timing
   with the `stop-timing` function.

   Typically you would use `with-timer`; for cases where you cannot,
   e.g. when using a non-closure callback, this is appropriate."
  [^Timer t]
  (.time t))

(defn stop-timing
  [^Timer$Context timing-handle]
  (.stop timing-handle))

(defmacro with-timer
  "More frequently, your timing can be declarative:

<pre><code>(with-timer my-timer
  (println \"Beginning to compute the 100,000th prime\")
  (nth-prime 100000)
  (println \"Finished\"))</code></pre>"
  [^Timer timer & forms]
  `(with-open [t# (start-timing ~timer)]
     ~@forms))

(defprotocol UpdateableMetric
  "Both timers and histograms expose methods named 'update'
   that take an integer parameter.  To keep reflection to a
   minimum, we use a protocol.  This way `update` can accommodate
   both metric types, and we don't sacrifice (much) performance."
  (update [metric count] "Updates the given metric"))

(extend-protocol UpdateableMetric
  Histogram
  (update [hist ^Integer count] (.update hist count))

  Timer
  (update [t millis]
    (.update t millis TimeUnit/MILLISECONDS)))

(defprotocol SingleValuedMetric
  "The five metric types each expose a scalar value, which depending on the specific
   type of metric is more or less useful.  Gauges and counters are of course simply
   measures of one scalar value, and so their value is the entirety of their information.

   Histograms, meters, and timers expose a raw count of events, but their value lies in
   their snapshots."
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

(defn mean-rate
  "The mean rate of a meter is interesting for trivia, but not much else."
  [^Metered meter-or-timer]
  (.getMeanRate meter-or-timer))

(defn one-minute-rate
  "The one-minute rate is useful for granular, to-the-minute rates."
  [^Metered meter-or-timer]
  (.getOneMinuteRate meter-or-timer))

(defn five-minute-rate
  [^Metered meter-or-timer]
  (.getFiveMinuteRate meter-or-timer))

(defn fifteen-minute-rate
  [^Metered meter-or-timer]
  (.getFifteenMinuteRate meter-or-timer))

(defn- ^TimeUnit unit-to-timeunit
  [unit]
  (cond
   (instance? TimeUnit unit) unit
   (keyword? unit) (keyword-to-timeunit unit)
   :else (throw (IllegalArgumentException. "Expected TimeUnit or keyword!"))))

(defn- mk-units-str
  [^TimeUnit unit ^String name]
  (let [s (clojure.string/lower-case (str unit))]
   (str name
        "/"
        (subs s 0 (- (count s) 1)))))

(defn rates
  "Gets the current rate of events for the given measurement."
  ([^Metered meter-or-timer]
     (rates meter-or-timer TimeUnit/SECONDS))
  ([^Metered meter-or-timer unit]
     (let [u (unit-to-timeunit unit)
           factor (.toSeconds u 1)]
      {:count (.getCount meter-or-timer)
       :15-minute-rate (* factor (fifteen-minute-rate meter-or-timer))
       :5-minute-rate (* factor (five-minute-rate meter-or-timer))
       :1-minute-rate (* factor (one-minute-rate meter-or-timer))
       :mean-rate (* factor (mean-rate meter-or-timer))
       :rate-units (mk-units-str u "events")})))

(defn snapshot
  "Gets a snapshot of the current distribution for the given measurement."
  ([^Sampling histogram-or-timer]
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
  ([^Timer timer unit]
     (let [s (.getSnapshot timer)
           u (unit-to-timeunit unit)
           factor (/ 1.0 (.toNanos u 1))]
       {:size (.size s)
        :min (* factor (.getMin s))
        :max (* factor (.getMax s))
        :mean (* factor (.getMean s))
        :std-dev (* factor (.getStdDev s))
        :median (* factor (.getMedian s))
        :75th-percentile (* factor (.get75thPercentile s))
        :95th-percentile (* factor (.get95thPercentile s))
        :98th-percentile (* factor (.get98thPercentile s))
        :99th-percentile (* factor (.get99thPercentile s))
        :999th-percentile (* factor (.get999thPercentile s))
        :duration-units (mk-units-str u "calls")})))

(defprotocol MeasurementReporter
  "## Reporting

   For measurements to be useful, they must first be made visible.  Several reporters
   are included that report measurements at regular intervals.

   Measurement reporters share a common interface - they all respond to `start!` and `stop!`
   functions.

   Reporters are not re-usable; due to their underlying implementations, once stopped they
   cannot be restarted."
  (start! [reporter] "Starts executing the measurement reporter.")
  (stop! [reporter] "Stops executing the measurement reporter.  Once stopeed, it cannot be restarted."))

;; ### Console Reporting
;;
;; Measurement registries can be printed to the console.  By default
;; rates are presented as events/second and durations as milliseconds.

(defrecord ConsoleMeasurementReporter [^ConsoleReporter reporter frequency ^TimeUnit time-unit]
  MeasurementReporter
  (start! [_] (.start reporter frequency time-unit))
  (stop! [_] (.stop reporter))

  Closeable
  (close [_] (.stop reporter)))

(defn console-reporter
  "`console-reporter` creates a reporter that periodically writes
   the given registry to the console.

   Metrics will be printed at a fixed interval, by default once
   per minute.

   Options are:

   * :rate-unit - A time unit (`:minutes`, `:seconds`, `:millis`, `:nanos`) to which to convert all rates.
   * :time-unit - A time  unit (`:minutes`, `:seconds`, `:millis`, `:nanos`) to which to convert all durations.
   * :frequency - How much time should pass between console outputs
   * :frequency-unit - A time unit (`:minutes`, `:seconds`, `:millis`, `:nanos`) describing the frequency; defaults to `:minutes`."
  [^MetricRegistry registry & {:keys [rate-unit time-unit frequency frequency-unit]
                                :or {frequency 1
                                     frequency-unit :minutes}}]
  (let [builder (cond-> (ConsoleReporter/forRegistry registry)
                        rate-unit (.convertRatesTo (keyword-to-timeunit rate-unit))
                        time-unit (.convertDurationsTo (keyword-to-timeunit time-unit)))
        reporter (.build builder)]
    (->ConsoleMeasurementReporter reporter frequency (keyword-to-timeunit frequency-unit))))

;; ### Graphite Reporting
;;
;; Measurements can be periodically sent to a server using the Graphite
;; plaintext protocol.

(defrecord GraphiteMeasurementReporter [^GraphiteReporter reporter frequency ^TimeUnit time-unit]
  MeasurementReporter
  (start! [_] (.start reporter frequency time-unit))
  (stop! [_] (.stop reporter))

  Closeable
  (close [_] (.stop reporter)))

(defn graphite-reporter
  "`graphite-reporter` creates a reporter for the given registry that
   sends measurements to a Graphite server.

   Metrics will be sent at a fixed interval, defaulting once every
   five seconds.  Metrics are sent using the names with which they
   were registered; if given, an optional prefix is prepended to each
   name.

   Options are:

   * :port - The port on which graphite is listening; defaults to 2003
   * :prefix - A string to be prefixed to the name of each metric.
   * :rate-unit - A time unit (`:minutes`, `:seconds`, `:millis`, `:nanos`) to which to conver all rates.
   * :time-unit - A time unit (`:minutes`, `:seconds`, `:millis`, `:nanos`) to which to convert all durations.
   * :frequency - How much time should pass between submissions; defaults to 1.
   * :frequency-unit - A time unit (`:minutes`, `:seconds`, `:millis`, `:nanos`) describing the frequency; defaults to `:seconds`."
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
    (->GraphiteMeasurementReporter reporter frequency (keyword-to-timeunit frequency-unit))))
