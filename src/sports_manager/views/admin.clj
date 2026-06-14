(ns sports-manager.views.admin
  "Admin pages: home dashboard, user management, sports configuration."
  (:require [clojure.string :as str]
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
  [user events memberships active-tid]
  (let [active-membership (first (filter #(= active-tid (get-in % [:membership/tenant :tenant/id])) memberships))
        tenant-name (get-in active-membership [:membership/tenant :tenant/name])]
    (shared/doc "Sports Manager"
                [:div.flex.flex-wrap.items-center.justify-between.gap-y-3.mb-8.pb-6.border-b.border-base-300
                 [:div.flex.flex-col.gap-1
                  (when tenant-name
                    [:span.text-xs.font-medium.uppercase.tracking-wider.opacity-50 tenant-name])
                  [:nav.flex.flex-wrap.gap-x-6.gap-y-2.text-sm
                   [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/users"} "Manage users"]
                   [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/school/sports"} "Sports"]
                   [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/disputes"} "Disputes"]
                   [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/audit"} "Audit log"]
                   (when (> (count memberships) 1)
                     [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/select-tenant"} "Switch org"])]]
                 [:div.flex.items-center.gap-4
                  [:span.text-sm.opacity-60
                   (or (:user/name user) (:user/email user))]
                  [:form {:method "post" :action "/auth/logout"}
                   (shared/csrf-field)
                   [:button.btn.btn-sm.btn-ghost {:type "submit"} "Sign out"]]]]
                [:section
                 [:div.flex.items-center.justify-between.mb-4
                  [:h2.text-xl.font-semibold.m-0 "Events"]
                  [:a.btn.btn-primary.btn-sm {:href "/events/create"} "+ New event"]]]
                (if (seq events)
                  [:ul.flex.flex-col.gap-3
                   (for [e events]
                     (let [status (name (:event/status e))
                           badge-class (case status
                                         "published" "badge-success"
                                         "draft" "badge-neutral"
                                         "badge-outline")]
                       [:li
                        [:a.block.rounded-xl.border.border-base-300.bg-base-200.hover:bg-base-300.transition-colors.p-4.no-underline
                         {:href (str "/events/" (:event/id e))}
                         [:div.flex.items-center.gap-3
                          [:span.font-semibold.text-base.flex-1 (:event/name e)]
                          [:span.badge.badge-sm {:class badge-class} status]
                          (when-let [d (:event/start-at e)]
                            [:span.text-xs.opacity-50
                             (.format (java.text.SimpleDateFormat. "d MMM yyyy") d)])]]]))]
                  [:p.opacity-50.text-sm "No events yet. Create your first event above."]))))

(defn users-list
  "Users management page. `tenant-users` is the list of user entity maps.
  `all-roles` is a seq of role-name keywords."
  [current-user tenant-users all-roles & [{:keys [add-errors add-email]
                                           :or {add-errors {}}}]]
  (shared/doc "Manage users — Sports Manager"
              [:div#toast]
              [:div.flex.flex-wrap.items-center.justify-between.gap-y-3.mb-8.pb-6.border-b.border-base-300
               [:nav.flex.flex-wrap.gap-x-6.gap-y-2.text-sm
                [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/"} "Home"]
                [:strong "Users"]]
               [:div.flex.items-center.gap-4
                [:span.text-sm.opacity-60
                 (or (:user/name current-user) (:user/email current-user))]
                [:form {:method "post" :action "/auth/logout"}
                 (shared/csrf-field)
                 [:button.btn.btn-sm.btn-ghost {:type "submit"} "Sign out"]]]]
              [:section.mb-8
               [:h2.text-xl.font-semibold.mb-4 "Team members"]
               (if (seq tenant-users)
                 [:div.flex.flex-col.gap-4
                  (for [u (sort-by :user/email tenant-users)]
                    (let [uid (:user/firebase-uid u)
                          user-roles (into #{} (map :role/name) (:user/roles u))
                          status (name (or (:user/status u) :active))]
                      [:div.rounded-xl.border.border-base-300.bg-base-200.p-4
                       [:div.flex.flex-wrap.items-start.justify-between.gap-3.mb-3
                        [:div
                         (when-let [n (:user/name u)]
                           [:div.font-semibold n])
                         [:div.text-sm.opacity-60 (:user/email u)]]
                        [:div.flex.items-center.gap-3
                         [:span.badge.badge-sm.badge-outline status]
                         [:form {:method "post" :action (str "/users/" uid "/remove")}
                          (shared/csrf-field)
                          [:button.btn.btn-sm.btn-error.btn-outline {:type "submit"} "Remove"]]]]
                       [:form {:hx-post (str "/users/" uid "/roles")
                               :hx-target "#toast"
                               :hx-swap "innerHTML"}
                        (shared/csrf-field)
                        [:div.flex.flex-wrap.gap-x-4.gap-y-2.mb-3
                         (for [rn (sort all-roles)]
                           (role-checkbox rn (contains? user-roles rn)))]
                        [:button.btn.btn-sm.btn-primary {:type "submit"} "Save roles"]]]))]
                 [:p.opacity-50 "No team members yet."])]
              [:section
               [:h2.text-xl.font-semibold.mb-1 "Add a team member"]
               [:p.text-sm.opacity-60.mb-4 "They must have signed in to Sports Manager at least once before you can add them."]
               [:form {:method "post" :action "/users/add"}
                (shared/csrf-field)
                [:div.flex.flex-wrap.gap-3.items-end
                 [:div.form-control
                  [:label.label [:span.label-text "Email address"]]
                  [:input.input.input-bordered
                   (cond-> {:id "email" :name "email" :type "email"
                            :placeholder "coach@school.ac.za" :required true}
                     add-email (assoc :value add-email))]
                  (when-let [e (:email add-errors)]
                    [:label.label [:span.label-text-alt.text-error e]])]
                 [:button.btn {:type "submit"} "Add user"]]
                (when-let [e (:not-found add-errors)]
                  [:p.text-error.mt-2 e])
                (when-let [e (:other-tenant add-errors)]
                  [:p.text-error.mt-2 e])]]))

(defn sport-templates
  "Sport template selection page."
  [all selected & [{:keys [custom-errors custom-data]
                    :or {custom-errors {} custom-data {}}}]]
  (let [platform (filter #(not= false (:sport-template/is-template %)) all)
        custom (filter #(false? (:sport-template/is-template %)) all)]
    (shared/doc "Sports — Sports Manager"
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href "/"} "← Home"]]
                 [:h2 "Sports"]]
                [:section
                 [:p "Select the sports your school offers. These will be available when creating events."]
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
                         [:span.badge.badge-ghost.badge-xs "custom"]]
                        [:form {:method "post"
                                :action (str "/school/sports/custom/" sid "/delete")
                                :class "inline"}
                         (shared/csrf-field)
                         [:button.btn.btn-xs.btn-ghost.text-error
                          {:type "submit"
                           :onclick "return confirm('Remove this custom sport?')"}
                          "✕"]]]))]
                  [:div.flex.gap-3.mt-4
                   [:button.btn {:type "submit"} "Save selection"]]]
                 [:div.divider]
                 [:section
                  [:h3.font-semibold.mb-3 "Add custom sport"]
                  [:form {:method "post" :action "/school/sports/custom"}
                   (shared/csrf-field)
                   [:div.grid.gap-3.grid-cols-1
                    [:div.form-control
                     [:label.label [:span.label-text "Sport name " [:span.text-error "*"]]]
                     [:input.input.input-bordered
                      {:type "text" :name "sport-name" :placeholder "e.g. Water Hockey"
                       :value (or (:sport-template/name custom-data) "")}]
                     (when-let [err (get custom-errors :sport-template/name)]
                       [:label.label [:span.label-text-alt.text-error err]])]
                    [:div.form-control
                     [:label.label [:span.label-text "Venue type"]]
                     [:select.select.select-bordered {:name "sport-venue-type"}
                      [:option {:value ""} "Not specified"]
                      (for [[kw label] [[:venue.type/field "Field"]
                                        [:venue.type/court "Court"]
                                        [:venue.type/pool "Pool"]
                                        [:venue.type/track "Track"]
                                        [:venue.type/other "Other"]]]
                        [:option {:value (str (namespace kw) "/" (name kw))
                                  :selected (= kw (:sport-template/venue-type custom-data))}
                         label])]]
                    [:div.form-control
                     [:label.label
                      [:span.label-text "Scoring increments"]
                      [:span.label-text-alt "EDN e.g. [5 3 2 1]"]]
                     [:input.input.input-bordered
                      {:type "text" :name "sport-scoring-increments"
                       :placeholder "[1]"
                       :value (or (:sport-template/scoring-increments custom-data) "")}]]
                    [:div.form-control
                     [:label.label
                      [:span.label-text "Period labels"]
                      [:span.label-text-alt "comma-separated"]]
                     [:input.input.input-bordered
                      {:type "text" :name "sport-period-labels"
                       :placeholder "Half,Half"
                       :value (or (:sport-template/period-labels custom-data) "")}]]]
                   [:div.mt-4
                    [:button.btn.btn-primary {:type "submit"} "Add sport"]]]]])))
