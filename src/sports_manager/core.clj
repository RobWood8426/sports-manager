(ns sports-manager.core
  "Entry point. Boots the Jetty web server and an embedded nREPL so that
  clojure-mcp (alias :mcp) can attach to the live process on port 7888."
  (:require [clojure.tools.logging :as log]
            [nrepl.server :as nrepl]
            [ring.adapter.jetty :as jetty]
            [sports-manager.auth :as auth]
            [sports-manager.db :as db]
            [sports-manager.rbac :as rbac]
            [sports-manager.routes.core :as routes.core]
            [sports-manager.sport-template :as sport-template])
  (:gen-class))

(defonce ^:private server (atom nil))
(defonce ^:private nrepl-server (atom nil))

(defn- env-int
  "Read an integer from env var k, falling back to default."
  [k default]
  (if-let [v (System/getenv k)]
    (Integer/parseInt v)
    default))

(defn start-nrepl!
  "Start an embedded nREPL server (idempotent). cider-nrepl middleware is only
  present under the :dev alias; we wrap the require so a bare :run still boots."
  [port]
  (when-not @nrepl-server
    (let [handler (try
                    (require 'cider.nrepl)
                    ((resolve 'nrepl.server/default-handler)
                     (resolve 'cider.nrepl/cider-middleware))
                    (catch Throwable _
                      (nrepl/default-handler)))]
      (reset! nrepl-server (nrepl/start-server :port port :handler handler))
      (spit ".nrepl-port" port)
      (log/info "nREPL server listening on port" port)))
  @nrepl-server)

(defn start!
  "Start the database then the web server (idempotent). Returns the Jetty
  server instance. Accepts an optional :handler to override the default
  routes/handler (e.g. wrapped with ring.middleware.reload in dev)."
  ([] (start! {}))
  ([{:keys [port join? handler] :or {port (env-int "PORT" 3000) join? false handler routes.core/handler}}]
   (db/start! [rbac/seed-roles! sport-template/seed-templates!])
   (auth/init!)
   (when-not @server
     (reset! server (jetty/run-jetty handler {:port port :join? join? :host "0.0.0.0"}))
     (log/info "Web server listening on http://localhost:" port))
   @server))

(defn stop!
  "Stop the web server, nREPL, and release the database. Safe to call when
  nothing is running."
  []
  (when @server (.stop ^org.eclipse.jetty.server.Server @server) (reset! server nil))
  (when @nrepl-server (nrepl/stop-server @nrepl-server) (reset! nrepl-server nil))
  (db/stop!))

(defn -main
  "Boot nREPL (for clojure-mcp) then the web server, joining the Jetty thread."
  [& _args]
  (start-nrepl! (env-int "NREPL_PORT" 7888))
  (start! {:join? true}))
