(ns sports-manager.views.shared
  "Shared layout helpers and UI fragments used across all view namespaces."
  (:require [hiccup2.core :as h]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [sports-manager.i18n :as i18n]))

(defn csrf-field
  "Hidden input carrying the CSRF token. Include once inside every POST form.

  Falls back to an empty value when `*anti-forgery-token*` is unbound — this
  happens when a page is rendered outside a request (e.g. the static CSRF-error
  response built at startup), where rendering the literal unbound-var string
  would be wrong. Such pages have no valid token anyway."
  []
  [:input {:type "hidden" :name "__anti-forgery-token"
           :value (if (bound? #'*anti-forgery-token*) *anti-forgery-token* "")}])

(def ^:private nav-items
  "Persistent admin global-nav: [active-key i18n-key href]. Labels are resolved
  per-request via `i18n/t` so the nav follows the viewer's language."
  [[:events :nav/events "/"]
   [:users :nav/users "/users"]
   [:sports :nav/sports "/school/sports"]
   [:settings :nav/settings "/school/settings"]
   [:disputes :nav/disputes "/disputes"]
   [:audit :nav/audit "/audit"]])

(defn lang-switcher
  "A compact language picker: a select that POSTs to /lang and reloads the page
  in the chosen language. `lang` is the currently-active code."
  [lang]
  [:form.inline {:method "post" :action "/lang"}
   (csrf-field)
   [:select.select.select-xs.select-bordered
    {:name "lang" :aria-label (i18n/t lang :lang/label)
     :onchange "this.form.submit()"}
    (for [code i18n/supported]
      [:option (cond-> {:value code} (= code lang) (assoc :selected true))
       (get i18n/lang-names code code)])]])

(defn- brand
  "SchoolScore logo lockup (design-system `Logo`): the scoreboard mark + the
  wordmark — \"School\" at full strength, \"Score\" in brand blue, Archivo
  extrabold. `size` is the mark edge in px. `href` is the link target (default
  \"/\" for authed pages; public pages pass a public-safe destination)."
  ([] (brand 28 "/"))
  ([size] (brand size "/"))
  ([size href]
   [:a.flex.items-center.shrink-0
    {:href href :style (str "text-decoration:none;gap:" (Math/round (* size 0.42)) "px")}
    [:svg {:width size :height size :viewBox "0 0 64 64" :fill "none"
           :aria-hidden "true" :style "display:block;flex:none"}
     [:rect {:x "2" :y "2" :width "60" :height "60" :rx "16" :fill "#2e6bf0"}]
     [:rect {:x "15" :y "20" :width "13" :height "24" :rx "4" :fill "#ffffff" :fill-opacity "0.92"}]
     [:rect {:x "36" :y "20" :width "13" :height "24" :rx "4" :fill "#ffffff" :fill-opacity "0.5"}]
     [:circle {:cx "49" :cy "15" :r "6.5" :fill "#3ddc84" :stroke "#2e6bf0" :stroke-width "2.5"}]]
    [:span {:style (str "font-family:var(--ss-font-display);font-weight:800;"
                        "font-size:" (Math/round (* size 0.82)) "px;"
                        "letter-spacing:-0.02em;line-height:1;color:var(--text-strong)")}
     "School" [:span {:style "color:var(--color-primary)"} "Score"]]]))

(defn- admin-header
  "The standardized sticky admin header from the design system `Shell`: brand +
  global nav (active item at full strength, others muted) on the left, signed-in
  user + Sign out on the right. `active` is a nav key (see `nav-items`); when nil
  the nav is omitted (used by pages like org-selection that have no tenant
  context yet)."
  [{:keys [user active nav-count lang] :or {lang "en"}}]
  [:header.sticky.top-0.z-10.flex.flex-wrap.items-center.justify-between.gap-y-2
   {:style "padding:0.75rem 1.75rem;border-bottom:1px solid var(--color-base-300);background:var(--color-base-100)"}
   [:div.flex.items-center.gap-7.flex-wrap
    (brand 24)
    (when active
      [:nav.flex.flex-wrap.gap-x-5.gap-y-1.text-sm
       (for [[k label-key href] nav-items]
         [:a.transition-colors
          {:href href
           :style (if (= k active)
                    "color:var(--text-strong);font-weight:600"
                    "color:var(--text-muted);font-weight:400")}
          (i18n/t lang label-key)])
       (when (some? nav-count)
         [:a.transition-colors {:href "/select-tenant" :style "color:var(--text-muted)"}
          (i18n/t lang :nav/switch-org)])])]
   [:div.flex.items-center.gap-4
    (lang-switcher lang)
    (when user
      (list
       [:span.text-sm {:style "color:var(--text-subtle)"} (or (:user/name user) (:user/email user))]
       [:form {:method "post" :action "/auth/logout"}
        (csrf-field)
        [:button.btn.btn-sm.btn-ghost {:type "submit"} (i18n/t lang :nav/sign-out)]]))]])

(defn doc
  "Wrap body hiccup in a full HTML document with DaisyUI + HTMX loaded.

  An optional leading options map customises the standardized admin header:
    :user      signed-in user entity (shows email + Sign out)
    :active    active global-nav key (see `nav-items`); omit to hide the nav
    :nav-count membership count (any value shows the Switch org link)
  When no options map is supplied, a plain brand-only header is rendered (e.g.
  the error page).

  :lang sets the document language (and drives nav/header translation); it is
  threaded in from the request's resolved language and defaults to English."
  [title & args]
  (let [opts (when (map? (first args)) (first args))
        body (if opts (rest args) args)
        lang (i18n/normalize-lang (:lang opts))]
    (str
     "<!DOCTYPE html>"
     (h/html
      [:html {:lang lang :data-theme "dark"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title title]
        [:link {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
        [:link {:rel "apple-touch-icon" :href "/favicon.svg"}]
        [:link {:href "https://cdn.jsdelivr.net/npm/daisyui@5" :rel "stylesheet" :type "text/css"}]
        [:script {:src "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"}]
        [:script {:src "https://unpkg.com/htmx.org@2.0.3"}]
        [:link {:rel "stylesheet" :href "/css/app.css"}]]
       [:body
        (admin-header opts)
        (into [:main] body)
        [:script {:src "/js/form-spinner.js"}]]]))))

(defn doc-public
  "Full HTML document for public (unauthenticated) pages.

  An optional leading options map adds the design-system spectator `Shell`
  header (brand mark + wordmark on the left, an optional event-code chip on the
  right):
    :brand? true   render the public brand header
    :code  \"IHSD26\"  show a `code · IHSD26` chip on the right (implies :brand?)
  With no options map the page is chrome-free (the bare code-entry screens).

  The brand link points at a public destination (the event landing when a code
  is known, otherwise the code-entry page) — never an authed route like \"/\".

  :lang sets the document language and is threaded in from the request's
  resolved language (event default or visitor override); defaults to English."
  [title & args]
  (let [opts (when (map? (first args)) (first args))
        body (if opts (rest args) args)
        {:keys [code]} opts
        lang (i18n/normalize-lang (:lang opts))
        show-header? (boolean (or code (:brand? opts)))
        brand-href (if code (str "/e/" code) "/e")]
    (str
     "<!DOCTYPE html>"
     (h/html
      [:html {:lang lang :data-theme "dark"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title title]
        [:link {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
        [:link {:rel "apple-touch-icon" :href "/favicon.svg"}]
        [:link {:href "https://cdn.jsdelivr.net/npm/daisyui@5" :rel "stylesheet" :type "text/css"}]
        [:script {:src "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"}]
        [:link {:rel "stylesheet" :href "/css/app.css"}]
        [:link {:rel "manifest" :href "/manifest.json"}]
        [:meta {:name "theme-color" :content "#1a56db"}]]
       [:body
        (when show-header?
          [:header.flex.items-center.justify-between
           {:style "padding:0.875rem 1.25rem;border-bottom:1px solid var(--color-base-300);background:var(--color-base-100)"}
           (brand 22 brand-href)
           [:div.flex.items-center.gap-3
            (when code
              [:span.ss-mono {:style "font-size:var(--text-xs, 0.75rem);color:var(--text-subtle)"}
               (str "code · " code)])
            (lang-switcher lang)]])
        (into [:main.p-6.max-w-xl.mx-auto] body)]]))))

(defn field
  "A labelled form input. Shows an inline error when `errors` contains the field key."
  [errors field-key label & [{:keys [input-type placeholder required? value]
                              :or {input-type "text" required? false}}]]
  [:div.form-control
   [:label.label
    [:span.label-text label
     (when required? [:span.text-error " *"])]]
   [:input.input.input-bordered.w-full
    (cond-> {:id (name field-key)
             :name (name field-key)
             :type input-type}
      placeholder (assoc :placeholder placeholder)
      required? (assoc :required true)
      value (assoc :value value))]
   (when-let [err (get errors field-key)]
     [:label.label [:span.label-text-alt.text-error err]])])

(defn toast-fragment
  "Inline HTML fragment — a DaisyUI toast that auto-dismisses after 3s.
  Rendered as a bare fragment (no doc wrapper) for HTMX swaps."
  [message & [{:keys [toast-type] :or {toast-type :success}}]]
  (let [alert-class (case toast-type
                      :success "alert-success"
                      :error "alert-error"
                      "alert-info")]
    (str (h/html
          [:div#toast
           [:div {:class "toast toast-top toast-end"}
            [:div {:class (str "alert " alert-class " shadow-lg")}
             [:span message]]]])
         "<script>setTimeout(()=>{let t=document.getElementById('toast');if(t)t.innerHTML='';},3000)</script>")))

(defn event-status-badge
  "DaisyUI badge hiccup for an event status keyword."
  [status]
  (let [s (name status)
        cls (case s
              "published" "badge-success"
              "draft" "badge-neutral"
              "cancelled" "badge-ghost"
              "badge-outline")]
    [:span.badge.badge-sm {:class cls} s]))

(defn fixture-status-badge
  "DaisyUI badge hiccup for a fixture status keyword."
  [status]
  (let [s (name status)
        cls (case s
              "published" "badge-success"
              "draft" "badge-neutral"
              "cancelled" "badge-ghost"
              "badge-outline")]
    [:span.badge.badge-sm {:class cls} s]))

(defn participant-status-badge
  "DaisyUI badge hiccup for a participant status keyword."
  [status]
  (let [s (name status)
        cls (case s
              "confirmed" "badge-success"
              "invited" "badge-warning"
              "withdrawn" "badge-error"
              "badge-outline")]
    [:span.badge.badge-sm {:class cls} s]))

(defn scode-status-badge
  "DaisyUI badge hiccup for a scorekeeper code status keyword."
  [status]
  (let [s (name status)
        cls (case s
              "active" "badge-success"
              "used" "badge-info"
              "revoked" "badge-ghost"
              "expired" "badge-ghost"
              "badge-outline")]
    [:span.badge.badge-sm {:class cls} s]))

(defn final-score-status-badge
  "DaisyUI badge hiccup for a final-score status keyword."
  [status]
  (let [s (name status)
        cls (case s
              "accepted" "badge-success"
              "pending" "badge-warning"
              "disputed" "badge-error"
              "badge-outline")]
    [:span.badge.badge-sm {:class cls} s]))

(defn dashboard-bucket-badge
  "DaisyUI badge hiccup for a dashboard bucket keyword."
  [bucket label]
  (let [cls (case bucket
              :live "badge-success"
              :disputed "badge-error"
              :pending "badge-warning"
              :completed "badge-info"
              :no-activity "badge-ghost"
              "badge-outline")]
    [:span.badge.badge-sm {:class cls} label]))

(defn fixture-status-label
  "Human-readable label and colour class for a public-facing fixture, given its
  live score map (with :fixture/final-score-status, :fixture/start-at, :fixture/end-at)."
  [f]
  (let [now (java.util.Date.)
        end (:fixture/end-at f)
        start (:fixture/start-at f)
        fs (:fixture/final-score-status f)]
    (cond
      (#{:final-score.status/accepted} fs) ["Final Score" "text-primary font-semibold"]
      (#{:final-score.status/disputed} fs) ["Disputed" "text-error font-semibold"]
      (#{:final-score.status/pending} fs) ["Pending" "text-warning font-semibold"]
      (and end (.before end now)) ["Ended" "opacity-50"]
      (and start (.before start now)) ["Live" "text-success font-semibold"]
      :else ["Upcoming" "opacity-50"])))

(defn fixture-status-pill
  "Tint status pill for a public-facing fixture, matching the design system's
  StatusBadge: a translucent tinted fill + matching text + subtle border, pill
  radius. Live also gets the pulsing dot. `label` is one of the strings from
  `fixture-status-label` (Live / Final Score / Pending / Disputed / Ended / Upcoming).
  An optional `attrs` map is merged onto the pill span (e.g. an `:id` the
  spectator detail page's poller targets)."
  ([label] (fixture-status-pill label nil))
  ([label attrs]
   (let [;; [text+border colour token, tint background] per status tone
         [tone tint] (case label
                       "Live" ["var(--ss-accent)" "var(--ss-tint-accent)"]
                       "Final Score" ["var(--color-primary)" "color-mix(in oklch, var(--color-primary) 15%, transparent)"]
                       "Pending" ["var(--color-warning)" "color-mix(in oklch, var(--color-warning) 16%, transparent)"]
                       "Disputed" ["var(--color-error)" "color-mix(in oklch, var(--color-error) 16%, transparent)"]
                       ;; Ended / Upcoming — quiet, no fill
                       [nil nil])]
     (if tone
       [:span.inline-flex.items-center.gap-1.5.shrink-0
        (merge {:style (str "height:22px;padding:0 9px;border-radius:var(--ss-radius-pill);"
                            "font-size:0.6875rem;font-weight:600;line-height:1;white-space:nowrap;"
                            "color:" tone ";background:" tint ";"
                            "border:1px solid color-mix(in oklch, " tone " 28%, transparent)")}
               attrs)
        (when (= label "Live") [:span.ss-live-dot {:style "width:6px;height:6px"}])
        label]
       ;; quiet ghost pill for Ended / Upcoming
       [:span.inline-flex.items-center.shrink-0.opacity-50
        (merge {:style "height:22px;padding:0 9px;font-size:0.6875rem;font-weight:600;line-height:1;white-space:nowrap"}
               attrs)
        label]))))

(def ^:private collapse-threshold
  "Lists with more items than this are collapsed by default."
  6)

(defn collapsible-list
  "A `<details>` disclosure wrapping a long list. The summary shows `label` and
  the item `count`; the list starts open when small (count <= threshold) and
  collapsed when long, so admins aren't faced with a wall of rows.

  `body` is the already-rendered list hiccup (or an empty-state element).
  Pass `:open? true/false` to force the initial state. Native <details>/<summary>
  — no JS."
  [label cnt body & [{:keys [open?]}]]
  (let [open? (if (some? open?) open? (<= cnt collapse-threshold))]
    [:details (cond-> {:class "mb-4"} open? (assoc :open true))
     [:summary.cursor-pointer.select-none.flex.items-center.gap-2.mb-3.text-sm.font-medium
      {:style "list-style:none"}
      [:span.ss-label.m-0 label]
      [:span.badge.badge-sm.badge-outline cnt]
      [:span.opacity-40 "▾"]]
     body]))

(defn error-page
  "Generic error page. `status` is the HTTP status code, `message` is human-readable."
  [status message]
  (doc (str status " — Sports Manager")
       [:div.max-w-xl.mx-auto.mt-12.text-center
        [:h2.text-4xl.font-bold.mb-4 (str status)]
        [:p.opacity-60 message]
        [:div.mt-6
         [:a.btn {:href "/"} (i18n/t "en" :error/go-home)]]]))

(defn not-found-page
  "Public-facing 404 page for spectator routes. Links back to /e."
  [message]
  (doc-public "Not Found — Sports Manager"
              {:brand? true}
              [:div.text-center.py-16
               [:h2.text-4xl.font-bold.mb-4 "404"]
               [:p.opacity-60.mb-8 message]
               [:a.btn {:href "/e"} "Go to events"]]))
