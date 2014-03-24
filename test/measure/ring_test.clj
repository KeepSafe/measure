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

(ns measure.ring-test
  (:require [clojure.test :refer :all]
            [measure.core :as m]
            [measure.ring :refer :all]))

(deftest status-meters
  (testing "Creating a handler registers status meters"
    (let [r (m/registry)
          h (with-measurements identity r)
          meters (.getMeters r)]
      (is (.containsKey meters "responses.1XX"))
      (is (.containsKey meters "responses.2XX"))
      (is (.containsKey meters "responses.3XX"))
      (is (.containsKey meters "responses.4XX"))
      (is (.containsKey meters "responses.5XX"))))

  (testing "When a request yields an in-spec HTTP response, marks the right meter"
    (let [r (m/registry)
          f (constantly {:status (+ 100 (rand-int 100))})
          h (with-measurements f r)]
      (h :some-request)
      (is (= 1 (m/value (m/meter r "responses.1XX")))))

    (let [r (m/registry)
          f (constantly {:status (+ 200 (rand-int 100))})
          h (with-measurements f r)]
      (h :some-request)
      (is (= 1 (m/value (m/meter r "responses.2XX")))))

    (let [r (m/registry)
          f (constantly {:status (+ 300 (rand-int 100))})
          h (with-measurements f r)]
      (h :some-request)
      (is (= 1 (m/value (m/meter r "responses.3XX")))))

    (let [r (m/registry)
          f (constantly {:status (+ 400 (rand-int 100))})
          h (with-measurements f r)]
      (h :some-request)
      (is (= 1 (m/value (m/meter r "responses.4XX")))))
    
    (let [r (m/registry)
          f (constantly {:status (+ 500 (rand-int 100))})
          h (with-measurements f r)]
      (h :some-request)
      (is (= 1 (m/value (m/meter r "responses.5XX"))))))

  (testing "When a request yields a non-spec HTTP status, nothing gets marked."
    (let [r (m/registry)
          f (constantly {:status 600})
          h (with-measurements f r)]
      (h :some-request)
      (doseq [meter (.values (.getMeters r))]
        (is (= 0 (m/value meter))))))

  (testing "When an exception is thrown, the 5XX meter is marked"
    (let [r (m/registry)
          f (fn [&] (throw (Exception. "BOOM")))
          h (with-measurements f r)]
      (try (h :some-request)
           (is false "Expected an exception")
           (catch Exception e
             (is (= 1 (m/value (m/meter r "responses.5XX")))))))))

(deftest method-meters
  (testing "Given ring-specified HTTP methods, the appropriate meters are marked."
    (let [r (m/registry)
          f (constantly {:status 200})
          h (with-measurements f r)
          request! (fn [method] (h {:request-method method}))]
      (request! :get)
      (request! :put)
      (request! :post)
      (request! :delete)
      (request! :head)
      (request! :options)
      (is (= 1 (m/value (m/meter r "requests.gets"))))
      (is (= 1 (m/value (m/meter r "requests.puts"))))
      (is (= 1 (m/value (m/meter r "requests.posts"))))
      (is (= 1 (m/value (m/meter r "requests.heads"))))
      (is (= 1 (m/value (m/meter r "requests.deletes"))))
      (is (= 1 (m/value (m/meter r "requests.options")))))))
