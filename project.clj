(defproject ai-router/openai-sse-guard "0.1.0"
  :description "Bounded, provider-neutral observation of OpenAI-compatible SSE streams"
  :url "https://ai-router.dev/"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :scm {:name "git"
        :url "https://github.com/airouter-dev/openai-sse-guard-clojure"}
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [org.clojure/data.json "2.5.2"]]
  :source-paths ["src"]
  :test-paths ["test"]
  :deploy-repositories [["clojars"
                         {:url "https://repo.clojars.org"
                          :username :env/clojars_username
                          :password :env/clojars_token}]])
