(ns user
  "Loaded automatically in the REPL (dev path). Convenience fns for driving the
  server from a connected REPL / clojure-mcp session."
  (:require [ring.middleware.reload :refer [wrap-reload]]
            [sports-manager.core :as core]
            [sports-manager.routes :as routes]))

(defn go
  "Start the web server with wrap-reload (without joining) so the REPL stays interactive."
  []
  (core/start! {:join? false :handler (wrap-reload #'routes/handler)}))

(defn stop
  "Stop the web server."
  []
  (core/stop!))

(defn restart
  "Bounce the web server."
  []
  (stop)
  (go))

(comment
  (go)
  (restart)
  (stop))