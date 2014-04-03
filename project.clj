(defproject cljam "0.1.0-SNAPSHOT"
  :description "A DNA Sequence Alignment/Map (SAM) library for Clojure"
  :url "https://chrovis.github.io/cljam"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/tools.cli "0.3.1"]
                 [me.raynes/fs "1.4.5"]
                 [clj-sub-command "0.2.0"]
                 [bgzf4j "0.1.0"]]
  :plugins [[lein-midje "3.1.3"]
            [lein-bin "0.3.4"]
            [lein-marginalia "0.7.1"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]
                                  [criterium "0.4.3"]
                                  [cavia "0.1.2"]
                                  [primitive-math "0.1.3"]]
                   :global-vars {*warn-on-reflection* true}}}
  :main cljam.main
  :aot [cljam.main]
  :bin {:name "cljam"}
  :repl-options {:init-ns user})
