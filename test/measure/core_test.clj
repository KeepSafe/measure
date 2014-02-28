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

(ns measure.core-test
  (:require [clojure.test :refer :all]
            [measure.core :refer :all])
  (:import [java.util.concurrent TimeUnit]
           [com.codahale.metrics MetricRegistry]))

(defn contains-key?
  [registry name]
  (.containsKey (.getMetrics registry) name))

(deftest registry-test
  (testing "Creating a new registry does what you think it does"
    (is (not (nil? (registry))))
    (is (instance? MetricRegistry (registry)))))

(deftest gauges
  (testing "Creating a gauge registers it"
    (let [r (registry)
          c (gauge r "time" #(System/currentTimeMillis))]
      (is (contains-key? r "time"))))

  (testing "Reading a fn-gauge invokes the fn"
    (let [r (registry)
          ctr (atom 0)
          g (gauge r "counter" #(swap! ctr inc))]
      (value g)
      (value g)
      (is (= 2 @ctr))))

  (testing "Reating an atom-gauge reads from the atom"
    (let [r (registry)
          a (atom :foo)
          g (gauge r "atom" a)]
      (is (= :foo (value g)))))

  (testing "Creating a derived gauge registers it"
    (let [r (registry)
          g (gauge r "time" #(System/currentTimeMillis))
          dg (derive-gauge r "time-str" g str)]
      (is (contains-key? r "time-str"))))

  (testing "Reading a derived gauge reads the underlying gauge"
    (let [r (registry)
          a (atom 0)
          g (gauge r "atom" #(swap! a inc))
          dg (derive-gauge r "atom-str" g str)]
      (value dg)
      (is (= 1 @a))))

  (testing "Reading a derived gauge applies the transformation to the underlying value"
    (let [r (registry)
          g (gauge r "atom" (atom 1))
          dg (derive-gauge r "atom-str" g str)]
      (is (= "1" (value dg)))))

  (testing "Creating a ratio gauge registers it"
    (let [r (registry)
          g (ratio r "half" #(1) #(2))]
      (is (contains-key? r "half"))))

  (testing "The value of a ratio gauge is the result of its numerator fn divided by its denominator fn"
    (let [r (registry)
          g (ratio r "half" (fn [] 1) (fn [] 2))] ;; For some reason, #(1) and #(2) causes a ClassCastException
      (is (= 0.5 (value g))))))

(deftest counters
  (testing "Creating a counter registers it"
    (let [r (registry)
          c (counter r "queue.length")]
      (is (contains-key? r "queue.length"))))

  (testing "Incrementing a counter increases its value by 1"
    (let [c (counter (registry) "counter")
          v (value c)]
      (increment c)
      (is (= (value c) (+ v 1)))))

  (testing "Decrementing a counter decreases its value by 1"
    (let [c (counter (registry) "counter")
          _ (increment c)
          v (value c)]
      (decrement c)
      (is (= (value c) (- v 1)))))

  (testing "Incrementing a counter by N increses its value by N"
    (let [c (counter (registry) "counter")
          v (value c)
          n (rand-int 50)]
      (increment c n)
      (is (= (value c) (+ v n)))))

  (testing "Decrementing a counter by N decreases its value by N"
    (let [c (counter (registry) "counter")
          _ (increment c 50)
          n (rand-int 50)
          v (value c)]
      (decrement c n)
      (is (= (value c) (- v n))))))

(deftest histograms
  (testing "Creating a histogram registers it"
    (let [r (registry)
          h (histogram r "search.results.counts")]
      (is (contains-key? r "search.results.counts"))))

  (testing "Updating a histogram tracks the distribution of its values"
    (let [h (histogram (registry) "foo")]
      (doseq [n (range 1 11)] ; numbers 1-10, inclusive
        (update h n))
      (is (= (.. h (getSnapshot) (getValue 0.0)) 1.0))
      (is (= (.. h (getSnapshot) (getValue 0.5)) 5.5))
      (is (= (.. h (getSnapshot) (getValue 1.0)) 10.0)))))

(deftest meters
  (testing "Creating a meter registers it"
    (let [r (registry)
          m (meter r "foo.bar")]
      (is (contains-key? r "foo.bar"))))

  (testing "Marking a meter records an event"
    (let [m (meter (registry) "nut-consumed-by-squirrel")]
      (is (= 0 (value m)))
      (mark m)
      (is (= 1 (value m)))))

  (testing "Marking a meter by N records N events"
    (let [m (meter (registry) "valves.frozzled")
          n (rand-int 100)]
      (is (= 0 (value m)))
      (mark m n)
      (is (= n (value m))))))

(deftest timers
  (testing "Creating a timer registers it"
    (let [r (registry)
          t (timer r "responses")]
      (is (contains-key? r "responses"))))

  (testing "time! returns the result of the given function"
    (is (= :foo (time! (timer (registry) "foo") (fn [] :foo)))))

  (testing "time! updates the timer's count"
    (let [t (timer (registry) "foo")]
      (time! t (fn [] (+ 1 2 3 4 5)))
      (is (= 1 (value t)))))

  (testing "timed-fn wraps a fn in a call to time!"
    (let [t (timer (registry) "bar")
          f #(+ 1 2 3 4 5)
          tf (timed-fn t f)]
      (f)
      (is (= 0 (value t)))
      (tf)
      (is (= 1 (value t)))))

  (testing "start-timing returns a closeable handle"
    (let [t (timer (registry) "blah")
          c (start-timing t)]
      (is (instance? java.io.Closeable c))))

  (testing "stop-timing, given the handle returne dfrom start-timing, updates the timer"
    (let [t (timer (registry) "egg")
          c (start-timing t)]
      (is (= 0 (value t)))
      (stop-timing c)
      (is (= 1 (value t)))))

  (testing "with-timer executes a list of expressions and times the total execution time."
    (let [t (timer (registry) "baz")
          fact (fn [n acc]
                 (if (= n 0)
                   acc
                   (recur (dec n) (*' acc n))))]
      (with-timer t
        (+ 1 2 3 4 5)
        (doseq [n (range 100)]
          (fact n 1)))
      (is (= 1 (value t))))))

(deftest single-valued-metrics
  (testing "(value a-gauge) gives the value of the gauge"
    (let [a (atom (rand-int 100))
          g (gauge (registry) "foo" a)]
      (is (= (value g) (.getValue g)))))

  (testing "(value a-counter) gives the count of the counter"
    (let [c (counter (registry) "foo")]
      (increment c (rand-int 100))
      (is (= (value c) (.getCount c)))))

  (testing "(value a-histogram) gives the count of the histogram"
    (let [h (histogram (registry) "bar")]
      (doseq [n (range 100)]
        (update h n))
      (is (= (value h) (.getCount h)))))

  (testing "(value a-meter) gives the count of the meter"
    (let [m (meter (registry) "baz")]
      (mark m (rand-int 100))
      (is (= (value m) (.getCount m)))))

  (testing "(value a-timer) gives the count of events in the timer"
    (let [t (timer (registry) "quux")]
      (doseq [i (range (rand-int 100))]
        (time! t (fn [] :foo)))
      (is (= (value t) (.getCount t))))))

(deftest snapshots
  (let [h (histogram (registry) "snap")
        _ (update h 100)
        s (snapshot h)]
    (testing "snapshot has all specified keys"
      (let [ks #{:size :min :max :mean :median :std-dev
                 :75th-percentile :95th-percentile :98th-percentile
                 :99th-percentile :999th-percentile}]
        (is (empty? (filter (comp not ks) (keys s))))))))

(deftest metered-rates
  (let [m (meter (registry) "metered")
        _ (doseq [n (range 1000)]
            (mark m))
        r (rates m)]
    (testing "rates have all specified keys"
      (let [ks #{:count :mean-rate :1-minute-rate :5-minute-rate
                 :15-minute-rate}]
        (is (empty? (filter (comp not ks) (keys r))))))))

(deftest console-reporting
  (testing "can handle :minutes, :seconds, :millis, and :nanos"
    (let [r (registry)]
      (console-reporter r :rate-unit :minutes)
      (console-reporter r :rate-unit :seconds)
      (console-reporter r :rate-unit :millis)
      (console-reporter r :rate-unit :nanos)
      (try (console-reporter r :rate-unit :hours)
           (catch IllegalArgumentException e)))))
