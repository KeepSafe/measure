(defproject measure "0.1.7-SNAPSHOT"
  :description "Say things about your application with authority, using Coda Hale's Metrics."
  :url "http://measure.getkeepsafe.com"
  :scm {:name "git"
        :url "https://github.com/KeepSafe/measure"}
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [io.dropwizard.metrics/metrics-core "3.1.0"]
                 [io.dropwizard.metrics/metrics-graphite "3.1.0"]]

  :profiles {:dev {:plugins [[com.jakemccrary/lein-test-refresh "0.3.4"]
                             [lein-marginalia "0.8.0-SNAPSHOT"]]
                   :dependencies [[org.slf4j/slf4j-simple "1.7.5"]]}}

  :repositories {"sonatype" {:url "http://oss.sonatype.org/content/repositories/releases"
                             :snapshots false
                             :releases {:checksum :fail :update :always}}}

  :signing {:gpg-key "ben@getkeepsafe.com"}

  :deploy-repositories [["clojars" {:creds :gpg}]]

  :pom-addition [:developers [:developer
                              [:name "Ben Bader"]
                              [:url "http://getkeepsafe.com"]
                              [:email "ben@getkeepsafe.com"]
                              [:timezone "America/Los Angeles"]]]
)
