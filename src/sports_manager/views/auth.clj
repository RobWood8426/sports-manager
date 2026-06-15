(ns sports-manager.views.auth
  "Auth and onboarding pages: login, school setup, org selection."
  (:require [hiccup2.core :as h]
            [sports-manager.views.shared :as shared]))

(defn login
  "Sign-in / sign-up page. Loads the Firebase web SDK and offers Google sign-in
  plus email/password sign-in and sign-up. On success it posts the resulting ID
  token to /auth/session, which sets the session cookie and redirects.
  `fb` is the public Firebase web config map."
  [{:keys [api-key auth-domain project-id app-id]}]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en" :data-theme "dark"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "Sign in — Sports Manager"]
      [:link {:href "https://cdn.jsdelivr.net/npm/daisyui@5" :rel "stylesheet" :type "text/css"}]
      [:script {:src "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"}]
      [:link {:rel "stylesheet" :href "/css/app.css"}]]
     [:body
      [:header
       [:a {:href "/" :class "flex items-center gap-3" :style "text-decoration:none;color:inherit"}
        [:img {:src "/mark.svg" :alt "" :width "28" :height "28"}]
        [:h1 "Sports Manager"]]]
      [:main
       [:div.ss-card.max-w-sm.mx-auto.mt-16.bg-base-100.shadow-xl.p-8
        [:h2.mb-1 "Sign in"]
        [:p.opacity-60.text-sm.mb-5 "Sign in or create an account to manage your school."]
        [:button.btn.btn-outline.w-full {:id "google" :type "button"}
         [:svg {:width "18" :height "18" :viewbox "0 0 18 18" :aria-hidden "true"}
          [:path {:fill "#4285F4" :d "M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.71-1.57 2.68-3.89 2.68-6.62z"}]
          [:path {:fill "#34A853" :d "M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z"}]
          [:path {:fill "#FBBC05" :d "M3.97 10.72A5.4 5.4 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.95H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.05l3.01-2.33z"}]
          [:path {:fill "#EA4335" :d "M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.59C13.46.89 11.43 0 9 0A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z"}]]
         [:span "Continue with Google"]]
        [:div.divider "or"]
        [:form {:id "login"}
         [:div.flex.flex-col.gap-3
          [:input.input.input-bordered.w-full {:id "email" :type "email" :placeholder "Email" :required true}]
          [:input.input.input-bordered.w-full {:id "password" :type "password" :placeholder "Password" :required true :minlength "6"}]
          [:div.flex.gap-3
           [:button.btn.flex-1 {:type "submit"} "Sign in"]
           [:button.btn.btn-outline.flex-1 {:id "signup" :type "button"} "Create account"]]]]
        [:p.text-error {:id "error"}]]]
      [:script {:type "module"}
       (h/raw
        (str
         "import { initializeApp } from 'https://www.gstatic.com/firebasejs/10.12.0/firebase-app.js';"
         "import { getAuth, GoogleAuthProvider, signInWithPopup,"
         "  signInWithEmailAndPassword, createUserWithEmailAndPassword }"
         "  from 'https://www.gstatic.com/firebasejs/10.12.0/firebase-auth.js';"
         "const app = initializeApp({"
         "  apiKey: '" api-key "',"
         "  authDomain: '" auth-domain "',"
         "  projectId: '" project-id "',"
         "  appId: '" app-id "'"
         "});"
         "const auth = getAuth(app);"
         "const err = document.getElementById('error');"
         "async function establish(cred) {"
         "  const token = await cred.user.getIdToken();"
         "  const res = await fetch('/auth/session', {"
         "    method: 'POST',"
         "    headers: {'Content-Type': 'application/json'},"
         "    body: JSON.stringify({ token })"
         "  });"
         "  if (res.ok) { window.location = '/'; }"
         "  else { err.textContent = 'Sign-in rejected.'; }"
         "}"
         "function fail(e) { err.textContent = e.message; }"
         "const email = () => document.getElementById('email').value;"
         "const pass  = () => document.getElementById('password').value;"
         "document.getElementById('google').addEventListener('click', async () => {"
         "  try { await establish(await signInWithPopup(auth, new GoogleAuthProvider())); }"
         "  catch (e) { fail(e); }"
         "});"
         "document.getElementById('login').addEventListener('submit', async (e) => {"
         "  e.preventDefault();"
         "  try { await establish(await signInWithEmailAndPassword(auth, email(), pass())); }"
         "  catch (e) { fail(e); }"
         "});"
         "document.getElementById('signup').addEventListener('click', async () => {"
         "  try { await establish(await createUserWithEmailAndPassword(auth, email(), pass())); }"
         "  catch (e) { fail(e); }"
         "});"))]]])))

(defn school-setup
  "School profile creation form. `errors` is a map of field-key → message."
  [& [{:keys [errors email] :or {errors {}}}]]
  (shared/doc "Set up your school — Sports Manager"
              [:div.max-w-2xl.mx-auto
               [:div.flex.justify-between.items-center.mb-4
                [:h2.m-0 "Set up your school"]
                [:form {:method "post" :action "/auth/logout"}
                 (shared/csrf-field)
                 [:button.btn.btn-ghost.btn-sm {:type "submit"} "Sign out"]]]
               [:p "Complete your school profile to get started."]
               [:form {:method "post" :action "/school/setup"}
                (shared/csrf-field)
                [:fieldset
                 [:legend.ss-label.mb-2 "School details"]
                 (shared/field errors :tenant/name "School name" {:required? true})
                 (shared/field errors :tenant/contact-email "Contact email" {:type "email" :required? true :value email})
                 (shared/field errors :tenant/contact-phone "Contact phone")
                 (shared/field errors :tenant/website "Website" {:type "url" :placeholder "https://"})]
                [:fieldset
                 [:legend.ss-label.mb-2 "Location"]
                 (shared/field errors :tenant/address "Street address")
                 (shared/field errors :tenant/city "City")
                 (shared/field errors :tenant/province "Province / State")
                 (shared/field errors :tenant/country "Country")]
                [:fieldset
                 [:legend.ss-label.mb-2 "Map location (optional)"]
                 [:div.flex.flex-wrap.gap-3.items-end
                  (shared/field errors :tenant/latitude "Latitude" {:placeholder "-33.9249"})
                  (shared/field errors :tenant/longitude "Longitude" {:placeholder "18.4241"})]]
                [:div.flex.gap-3.mt-4
                 [:button.btn.btn-primary {:type "submit"} "Create school"]]]]))

(defn select-tenant
  "Org picker page shown when a user belongs to multiple tenants."
  [memberships]
  (shared/doc "Select organisation — Sports Manager"
              [:div.max-w-lg.mx-auto
               [:h2.text-2xl.font-semibold.mb-2 "Select organisation"]
               [:p.opacity-60.mb-6 "Which organisation would you like to manage?"]
               [:ul.flex.flex-col.gap-3
                (for [m memberships]
                  (let [tenant (:membership/tenant m)
                        tid (:tenant/id tenant)
                        tname (or (:tenant/name tenant) (str tid))
                        tcity (:tenant/city tenant)]
                    [:li
                     [:form {:method "post" :action "/select-tenant"}
                      (shared/csrf-field)
                      [:input {:type "hidden" :name "tenant-id" :value (str tid)}]
                      [:button.block.w-full.text-left.rounded-xl.border.border-base-300.bg-base-200.hover:bg-base-300.transition-colors.p-4
                       {:type "submit"}
                       [:div.flex.items-center.justify-between
                        [:div
                         [:div.font-semibold tname]
                         (when tcity [:div.text-sm.opacity-60 tcity])]
                        [:span.text-sm.opacity-40 "→"]]]]]))]]))
