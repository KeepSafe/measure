(defproject measure "0.1.0-SNAPSHOT"
  :description "Say things about your application with authority, using Coda Hale's Metrics."
  :url "https://github.com/benjamin-bader/measure"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [com.codahale.metrics/metrics-core "3.0.1"]
                 [com.codahale.metrics/metrics-graphite "3.0.1"]]

  :profiles {:dev {:plugins [[com.jakemccrary/lein-test-refresh "0.3.4"]
                             [lein-marginalia "0.7.1"]]}}

  :repositories {"sonatype" {:url "http://oss.sonatype.org/content/repositories/releases"
                             :snapshots false
                             :releases {:checksum :fail :update :always}}}
)
