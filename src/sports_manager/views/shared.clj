(ns sports-manager.views.shared
  "Shared layout helpers and UI fragments used across all view namespaces."
  (:require [hiccup2.core :as h]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]))

(defn doc
  "Wrap body hiccup in a full HTML document with DaisyUI + HTMX loaded."
  [title & body]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en" :data-theme "dark"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title title]
      [:link {:href "https://cdn.jsdelivr.net/npm/daisyui@5" :rel "stylesheet" :type "text/css"}]
      [:script {:src "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"}]
      [:script {:src "https://unpkg.com/htmx.org@2.0.3"}]
      [:link {:rel "stylesheet" :href "/css/app.css"}]]
     [:body
      [:header [:h1 [:a {:href "/" :style "text-decoration:none;color:inherit"} "Sports Manager"]]]
      (into [:main] body)]])))

(defn doc-public
  "Full HTML document for public (unauthenticated) pages — no admin header.
  Includes PWA manifest and service worker registration."
  [title & body]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en" :data-theme "dark"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title title]
      [:link {:href "https://cdn.jsdelivr.net/npm/daisyui@5" :rel "stylesheet" :type "text/css"}]
      [:script {:src "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"}]
      [:link {:rel "stylesheet" :href "/css/app.css"}]
      [:link {:rel "manifest" :href "/manifest.json"}]
      [:meta {:name "theme-color" :content "#1a56db"}]]
     [:body
      (into [:main.p-6.max-w-xl.mx-auto] body)
      [:script
       (h/raw
        "if ('serviceWorker' in navigator) {
           navigator.serviceWorker.register('/sw.js').catch(function(){});
         }")]]])))

(defn csrf-field
  "Hidden input carrying the CSRF token. Include once inside every POST form."
  []
  [:input {:type "hidden" :name "__anti-forgery-token" :value *anti-forgery-token*}])

(defn field
  "A labelled form input. Shows an inline error when `errors` contains the field key."
  [errors field-key label & [{:keys [type placeholder required?]
                              :or {type "text" required? false}}]]
  [:div.form-control
   [:label.label
    [:span.label-text label
     (when required? [:span.text-error " *"])]]
   [:input.input.input-bordered.w-full
    (cond-> {:id (name field-key)
             :name (name field-key)
             :type type}
      placeholder (assoc :placeholder placeholder)
      required? (assoc :required true))]
   (when-let [err (get errors field-key)]
     [:label.label [:span.label-text-alt.text-error err]])])

(defn toast-fragment
  "Inline HTML fragment — a DaisyUI toast that auto-dismisses after 3s.
  Rendered as a bare fragment (no doc wrapper) for HTMX swaps."
  [message & [{:keys [type] :or {type :success}}]]
  (let [alert-class (case type
                      :success "alert-success"
                      :error "alert-error"
                      "alert-info")]
    (str (h/html
          [:div#toast
           [:div {:class "toast toast-top toast-end"}
            [:div {:class (str "alert " alert-class " shadow-lg")}
             [:span message]]]])
         "<script>setTimeout(()=>{let t=document.getElementById('toast');if(t)t.innerHTML='';},3000)</script>")))

(defn error-page
  "Generic error page. `status` is the HTTP status code, `message` is human-readable."
  [status message]
  (doc (str status " — Sports Manager")
       [:div.max-w-xl.mx-auto.mt-12.text-center
        [:h2.text-4xl.font-bold.mb-4 (str status)]
        [:p.text-gray-600 message]
        [:div.mt-6
         [:a.btn {:href "/"} "Go home"]]]))
