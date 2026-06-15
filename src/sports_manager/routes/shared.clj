(ns sports-manager.routes.shared
  "Cross-cutting helpers shared by all route namespaces."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [ring.util.response :as resp]
            [sports-manager.auth :as auth]
            [sports-manager.membership :as membership]
            [sports-manager.session :as session]))

(defn html
  "Build a 200 text/html response from a string body."
  [body]
  (-> (resp/response body)
      (resp/content-type "text/html; charset=utf-8")))

(defn json-body
  "Parse a JSON request body into a keyword-keyed map (nil on failure)."
  [request]
  (when-let [b (:body request)]
    (try (json/parse-string (slurp (io/reader b)) true)
         (catch Exception _ nil))))

(defn form-params
  "Extract form-encoded params as keyword-keyed map."
  [request]
  (or (:form-params request) {}))

(defn request->base-url
  "Derive the base URL from the incoming request's Host header so that QR codes
  and links work regardless of hostname (dev, LAN IP, production domain, etc.)."
  [request]
  (let [host   (or (get-in request [:headers "x-forwarded-host"])
                   (get-in request [:headers "host"])
                   "localhost:3000")
        scheme (or (get-in request [:headers "x-forwarded-proto"])
                   (name (get request :scheme :http)))]
    (str scheme "://" host)))

(defn parse-event-id
  "Parse a UUID from a path param string, or nil on failure."
  [s]
  (when s
    (try (java.util.UUID/fromString s)
         (catch IllegalArgumentException _ nil))))

(defn require-tenant
  "Returns [user tenant-id membership] if request has an authenticated user with
  an active tenant selection, otherwise returns a redirect response as the first
  element with nil tenant-id."
  [request]
  (if-let [uid (:uid request)]
    (let [active-tid (session/active-tenant-id request)]
      (if-not active-tid
        [(resp/redirect "/select-tenant") nil nil]
        (let [m (membership/active? uid active-tid)]
          (if-not m
            [(resp/redirect "/select-tenant") nil nil]
            [(auth/find-user uid) active-tid m]))))
    [(resp/redirect "/login") nil nil]))
