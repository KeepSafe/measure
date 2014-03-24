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

(ns measure.ring
  "One of the most prominent production paradigms in the Clojure
   ecosystem is the Ring web app.  Some measurements are of interest
   to virtually all web applications; the `measure.ring` namespace
   accommodates them."
  (:require [measure.core :as m]))

(defn- status->keyword
  "The measuring handler buckets response codes by the usual categories,
   i.e. in buckets of 1XX, 2XX, etc.  This function accepts a status code
   and returns a suitable bucket as a keyword."
  [status]
  (let [n (quot status 100)]
    (condp = n
      1 :1XX
      2 :2XX
      3 :3XX
      4 :4XX
      5 :5XX      
      (keyword (str n "XX")))))

(defn with-measurements
  "While monitoring concerns are as varied as applications themselves,
   some measurements are common across HTTP services.  Status codes
   and request methods are of universal interest, as are request
   sizes.

   This function returns a handler that measures rates of response
   code categories (100, 200, etc.) as well as the distribution of
   request body sizes in bytes."
  [handler registry]
  (let [status-meters {:5XX (m/meter registry "responses.5XX")
                       :4XX (m/meter registry "responses.4XX")
                       :3XX (m/meter registry "responses.3XX")
                       :2XX (m/meter registry "responses.2XX")
                       :1XX (m/meter registry "responses.1XX")}

        method-meters {:get     (m/meter registry "requests.gets")
                       :put     (m/meter registry "requests.puts")
                       :post    (m/meter registry "requests.posts")
                       :head    (m/meter registry "requests.heads")
                       :delete  (m/meter registry "requests.deletes")
                       :options (m/meter registry "requests.options")}

        request-size-hist (m/histogram registry "requests.size")]
    (fn [request]
      (try
        (when-let [method-meter (method-meters (:request-method request))]
          (m/mark method-meter))
        (when-let [content-length (:content-length request)]
          (m/update request-size-hist content-length))
        (let [response (handler request)
              status (:status response)
              kw (status->keyword status)
              status-meter (kw status-meters)]
          (when status-meter
            (m/mark status-meter))
          response)
        (catch Exception e
          (m/mark (:5XX status-meters))
          (throw e))))))
