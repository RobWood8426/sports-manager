(ns sports-manager.views.admin
  "Admin pages: home dashboard, user management, sports configuration."
  (:require [clojure.string :as str]
            [sports-manager.i18n :as i18n]
            [sports-manager.views.shared :as shared]))

(defn- role-name-label [role-name]
  (-> (name role-name)
      (str/replace #"^role\.name\." "")
      (str/replace "-" " ")
      str/capitalize))

(defn- role-checkbox [role-name checked?]
  (let [id (name role-name)]
    [:label.flex.items-center.gap-1.text-sm.cursor-pointer
     [:input (cond-> {:type "checkbox" :name "roles" :value id}
               checked? (assoc :checked true))]
     (role-name-label role-name)]))

(defn home
  "Signed-in landing page."
  [user events memberships _active-tid & [{:keys [tenant-name lang] :or {lang "en"}}]]
  (shared/doc "Sports Manager"
              {:user user :active :events :nav-count (count memberships) :lang lang}
              [:div.mb-8
               (when tenant-name
                 [:span.ss-label tenant-name])]
              [:section
               [:div.flex.items-center.justify-between.mb-4
                [:h2.text-2xl.m-0 (i18n/t lang :home/events-heading)]
                [:a.btn.btn-primary.btn-sm {:href "/events/create"} (i18n/t lang :home/new-event)]]]
              (if (seq events)
                [:ul.flex.flex-col.gap-3
                 (for [e events]
                   [:li
                    [:a.ss-card.block.hover:bg-base-300.transition-colors.p-4.no-underline
                     {:href (str "/events/" (:event/id e))}
                     [:div.flex.items-center.gap-3
                      [:span.font-semibold.text-base.flex-1 (:event/name e)]
                      (shared/event-status-badge (:event/status e))
                      (when-let [d (:event/start-at e)]
                        [:span.text-xs.opacity-50
                         (.format (java.text.SimpleDateFormat. "d MMM yyyy") d)])]]])]
                [:p.opacity-50.text-sm (i18n/t lang :home/no-events)])))

(defn users-list
  "Users management page. `tenant-users` is the list of user entity maps.
  `all-roles` is a seq of role-name keywords."
  [current-user tenant-users all-roles & [{:keys [add-errors add-email pending-invites invited? lang]
                                           :or {add-errors {} pending-invites [] lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))]
    (shared/doc (tr :users/page-title)
                {:user current-user :active :users :lang lang}
                [:div#toast]
                [:h2.text-2xl.mb-8 (tr :users/heading)]
                (when invited?
                  [:div.alert.alert-success.mb-6
                   [:span (tr :users/invite-sent)]])
                [:section.mb-8
                 [:h2.text-xl.font-semibold.mb-4 (tr :users/team-members)]
                 (if (seq tenant-users)
                   [:div.flex.flex-col.gap-4
                    (for [u (sort-by :user/email tenant-users)]
                      (let [uid (:user/firebase-uid u)
                            user-roles (into #{} (map :role/name) (:user/roles u))]
                        [:div.ss-card.p-4
                         [:div.flex.flex-wrap.items-start.justify-between.gap-3.mb-3
                          [:div
                           (when-let [n (:user/name u)]
                             [:div.font-semibold n])
                           [:div.text-sm.opacity-60 (:user/email u)]]
                          [:div.flex.items-center.gap-3
                           (shared/event-status-badge (or (:user/status u) :active))
                           [:form {:method "post" :action (str "/users/" uid "/remove")}
                            (shared/csrf-field)
                            [:button.btn.btn-sm.btn-error.btn-outline {:type "submit"} (tr :users/remove)]]]]
                         [:form {:hx-post (str "/users/" uid "/roles")
                                 :hx-target "#toast"
                                 :hx-swap "innerHTML"}
                          (shared/csrf-field)
                          [:div.flex.flex-wrap.gap-x-4.gap-y-2.mb-3
                           (for [rn (sort all-roles)]
                             (role-checkbox rn (contains? user-roles rn)))]
                          [:button.btn.btn-sm.btn-primary {:type "submit"} (tr :users/save-roles)]]]))]
                   [:p.opacity-50 (tr :users/no-members)])]
                (when (seq pending-invites)
                  [:section.mb-8
                   [:h2.text-xl.font-semibold.mb-4 (tr :users/pending-invites)]
                   [:div.flex.flex-col.gap-2
                    (for [inv (sort-by :invite/email pending-invites)]
                      [:div.ss-card.p-3.flex.items-center.justify-between
                       [:span.text-sm (:invite/email inv)]
                       [:span.badge.badge-outline.badge-sm (tr :users/awaiting-signin)]])]])
                [:section
                 [:h2.text-xl.font-semibold.mb-1 (tr :users/add-heading)]
                 [:p.text-sm.opacity-60.mb-4 (tr :users/add-intro)]
                 [:form {:method "post" :action "/users/add"}
                  (shared/csrf-field)
                  [:div.flex.flex-wrap.gap-3.items-end
                   [:div.form-control
                    [:label.label [:span.label-text (tr :users/email-label)]]
                    [:input.input.input-bordered
                     (cond-> {:id "email" :name "email" :type "email"
                              :placeholder (tr :users/email-placeholder) :required true}
                       add-email (assoc :value add-email))]
                    (when-let [e (:email add-errors)]
                      [:label.label [:span.label-text-alt.text-error e]])]
                   [:button.btn {:type "submit"} (tr :users/add-button)]]
                  (when-let [e (:other-tenant add-errors)]
                    [:p.text-error.mt-2 e])]])))

(defn sport-templates
  "Sport template selection page."
  [all selected & [{:keys [custom-errors custom-data lang]
                    :or {custom-errors {} custom-data {} lang "en"}}]]
  (let [platform (filter #(not= false (:sport-template/is-template %)) all)
        custom (filter #(false? (:sport-template/is-template %)) all)
        tr (fn [k] (i18n/t lang k))]
    (shared/doc (tr :sports/page-title)
                {:active :sports :lang lang}
                [:h2.text-2xl.mb-2 (tr :sports/heading)]
                [:section
                 [:p (tr :sports/intro)]
                 [:form {:method "post" :action "/school/sports"}
                  (shared/csrf-field)
                  [:div.grid.gap-3
                   {:class "grid-cols-[repeat(auto-fill,minmax(140px,1fr))]"}
                   (for [t (sort-by :sport-template/name platform)]
                     (let [code (name (:sport-template/code t))
                           checked? (contains? selected (:sport-template/code t))]
                       [:label.card.card-bordered.cursor-pointer.p-3.flex-row.items-center.gap-2.hover:border-primary.transition-colors
                        [:input (cond-> {:type "checkbox" :name "sports" :value code}
                                  checked? (assoc :checked true))]
                        [:span (:sport-template/name t)]]))
                   (for [t (sort-by :sport-template/name custom)]
                     (let [code (name (:sport-template/code t))
                           checked? (contains? selected (:sport-template/code t))
                           sid (str (:sport-template/id t))]
                       [:div.card.card-bordered.p-3.flex-row.items-center.gap-2.relative
                        [:label.flex-1.flex.items-center.gap-2.cursor-pointer
                         [:input (cond-> {:type "checkbox" :name "sports" :value code}
                                   checked? (assoc :checked true))]
                         [:span (:sport-template/name t)]
                         [:span.badge.badge-ghost.badge-xs (tr :sports/custom-badge)]]
                        [:form {:method "post"
                                :action (str "/school/sports/custom/" sid "/delete")
                                :class "inline"}
                         (shared/csrf-field)
                         [:button.btn.btn-xs.btn-ghost.text-error
                          {:type "submit"
                           :onclick (str "return confirm('" (tr :sports/remove-custom-confirm) "')")}
                          "✕"]]]))]
                  [:div.flex.gap-3.mt-4
                   [:button.btn {:type "submit"} (tr :sports/save-selection)]]]
                 [:div.divider]
                 [:section
                  [:h3.font-semibold.mb-3 (tr :sports/add-custom-heading)]
                  [:form {:method "post" :action "/school/sports/custom"}
                   (shared/csrf-field)
                   [:div.grid.gap-3.grid-cols-1
                    [:div.form-control
                     [:label.label [:span.label-text (tr :sports/name-label) " " [:span.text-error "*"]]]
                     [:input.input.input-bordered
                      {:type "text" :name "sport-name" :placeholder (tr :sports/name-placeholder)
                       :value (or (:sport-template/name custom-data) "")}]
                     (when-let [err (get custom-errors :sport-template/name)]
                       [:label.label [:span.label-text-alt.text-error err]])]
                    [:div.form-control
                     [:label.label [:span.label-text (tr :sports/venue-type)]]
                     [:select.select.select-bordered {:name "sport-venue-type"}
                      [:option {:value ""} (tr :sports/venue-not-specified)]
                      (for [[kw label-key] [[:venue.type/field :sports/venue-field]
                                            [:venue.type/court :sports/venue-court]
                                            [:venue.type/pool :sports/venue-pool]
                                            [:venue.type/track :sports/venue-track]
                                            [:venue.type/other :sports/venue-other]]]
                        [:option {:value (str (namespace kw) "/" (name kw))
                                  :selected (= kw (:sport-template/venue-type custom-data))}
                         (tr label-key)])]]
                    [:div.form-control
                     [:label.label
                      [:span.label-text (tr :sports/scoring-increments)]
                      [:span.label-text-alt (tr :sports/scoring-increments-hint)]]
                     [:input.input.input-bordered
                      {:type "text" :name "sport-scoring-increments"
                       :placeholder "[1]"
                       :value (or (:sport-template/scoring-increments custom-data) "")}]]
                    [:div.form-control
                     [:label.label
                      [:span.label-text (tr :sports/period-labels)]
                      [:span.label-text-alt (tr :sports/period-labels-hint)]]
                     [:input.input.input-bordered
                      {:type "text" :name "sport-period-labels"
                       :placeholder "Half,Half"
                       :value (or (:sport-template/period-labels custom-data) "")}]]]
                   [:div.mt-4
                    [:button.btn.btn-primary {:type "submit"} (tr :sports/add-sport)]]]]])))

(defn- logo-media-url
  "Build the public media URL for a tenant's logo from its stored key, or nil."
  [tenant]
  (when-let [k (:tenant/logo-key tenant)]
    (str "/media/" k)))

(defn school-settings
  "School settings page: profile, brand colours, and logo upload (SPO-23).
  `tenant` is the tenant entity map; `opts` may carry {:errors {...}}."
  [tenant & [{:keys [errors lang] :or {errors {} lang "en"}}]]
  (let [primary (or (:tenant/brand-primary tenant) "")
        secondary (or (:tenant/brand-secondary tenant) "")
        logo-url (logo-media-url tenant)
        field-err (fn [k] (when-let [e (get errors k)]
                            [:label.label [:span.label-text-alt.text-error e]]))
        tr (fn [k] (i18n/t lang k))]
    (shared/doc (tr :settings/page-title)
                {:active :settings :lang lang}
                [:h2.text-2xl.mb-2 (tr :settings/heading)]
                [:p.opacity-60.mb-6 (tr :settings/intro)]

                [:section.mb-10
                 [:h3.text-xl.font-semibold.mb-4 (tr :settings/profile-section)]
                 [:form {:method "post" :action "/school/settings"}
                  (shared/csrf-field)
                  [:div.grid.gap-4 {:class "grid-cols-1 sm:grid-cols-2"}
                   [:div.form-control
                    [:label.label [:span.label-text (tr :settings/school-name) " " [:span.text-error "*"]]]
                    [:input.input.input-bordered
                     {:type "text" :name "tenant-name" :value (or (:tenant/name tenant) "")}]
                    (field-err :tenant/name)]
                   [:div.form-control
                    [:label.label [:span.label-text (tr :settings/contact-email) " " [:span.text-error "*"]]]
                    [:input.input.input-bordered
                     {:type "email" :name "tenant-contact-email" :value (or (:tenant/contact-email tenant) "")}]
                    (field-err :tenant/contact-email)]
                   [:div.form-control
                    [:label.label [:span.label-text (tr :settings/website)]]
                    [:input.input.input-bordered
                     {:type "text" :name "tenant-website" :value (or (:tenant/website tenant) "")}]]
                   [:div.form-control
                    [:label.label [:span.label-text (tr :settings/city)]]
                    [:input.input.input-bordered
                     {:type "text" :name "tenant-city" :value (or (:tenant/city tenant) "")}]]
                   [:div.form-control
                    [:label.label [:span.label-text (tr :settings/province)]]
                    [:input.input.input-bordered
                     {:type "text" :name "tenant-province" :value (or (:tenant/province tenant) "")}]]]
                  [:div.divider.my-2 (tr :settings/brand-colours)]
                  [:div.grid.gap-4 {:class "grid-cols-1 sm:grid-cols-2"}
                   [:div.form-control
                    [:label.label [:span.label-text (tr :settings/primary-colour)]]
                    [:div.flex.items-center.gap-2
                     [:input {:type "color" :name "brand-primary"
                              :value (if (str/blank? primary) "#2e6bf0" primary)
                              :style "width:3rem;height:2.5rem;padding:2px;border:none;background:none;cursor:pointer"}]
                     [:span.text-sm.opacity-60 (if (str/blank? primary) (tr :settings/colour-default) primary)]]]
                   [:div.form-control
                    [:label.label [:span.label-text (tr :settings/secondary-colour)]]
                    [:div.flex.items-center.gap-2
                     [:input {:type "color" :name "brand-secondary"
                              :value (if (str/blank? secondary) "#3ddc84" secondary)
                              :style "width:3rem;height:2.5rem;padding:2px;border:none;background:none;cursor:pointer"}]
                     [:span.text-sm.opacity-60 (if (str/blank? secondary) (tr :settings/colour-default) secondary)]]]]
                  [:div.mt-4
                   [:button.btn.btn-primary {:type "submit"} (tr :settings/save)]]]]

                [:section
                 [:h3.text-xl.font-semibold.mb-4 (tr :settings/logo-section)]
                 (when (:file errors)
                   [:div.alert.alert-error.mb-4 [:span (:file errors)]])
                 (if logo-url
                   [:div.flex.items-center.gap-4.mb-4
                    [:img {:src logo-url :alt (tr :settings/logo-section)
                           :style "max-width:120px;max-height:120px;border-radius:var(--ss-radius);background:var(--color-base-200);padding:8px"}]
                    [:form {:method "post" :action "/school/settings/logo/clear"}
                     (shared/csrf-field)
                     [:button.btn.btn-sm.btn-outline.btn-error
                      {:type "submit" :onclick (str "return confirm('" (tr :settings/remove-logo-confirm) "')")}
                      (tr :settings/remove-logo)]]]
                   [:p.opacity-50.text-sm.mb-4 (tr :settings/no-logo)])
                 [:form {:method "post" :action "/school/settings/logo"
                         :enctype "multipart/form-data"}
                  (shared/csrf-field)
                  [:div.form-control
                   [:label.label [:span.label-text (tr :settings/upload-label)]]
                   [:input.file-input.file-input-bordered
                    {:type "file" :name "logo-file" :accept "image/png,image/jpeg,image/webp"}]
                   [:label.label [:span.label-text-alt (tr :settings/upload-hint)]]]
                  [:div.mt-3
                   [:button.btn {:type "submit"} (tr (if logo-url :settings/replace-logo :settings/upload-logo))]]]])))
