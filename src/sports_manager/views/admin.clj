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
  [user events memberships _active-tid & [{:keys [tenant-name]}]]
  (shared/doc "Sports Manager"
              {:user user :active :events :nav-count (count memberships)}
              [:div.mb-8
               (when tenant-name
                 [:span.ss-label tenant-name])]
              [:section
               [:div.flex.items-center.justify-between.mb-4
                [:h2.text-2xl.m-0 "Events"]
                [:a.btn.btn-primary.btn-sm {:href "/events/create"} "+ New event"]]]
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
                [:p.opacity-50.text-sm "No events yet. Create your first event above."])))

(defn users-list
  "Users management page. `tenant-users` is the list of user entity maps.
  `all-roles` is a seq of role-name keywords."
  [current-user tenant-users all-roles & [{:keys [add-errors add-email pending-invites invited?]
                                           :or {add-errors {} pending-invites []}}]]
  (shared/doc "Manage users — Sports Manager"
              {:user current-user :active :users}
              [:div#toast]
              [:h2.text-2xl.mb-8 "Manage users"]
              (when invited?
                [:div.alert.alert-success.mb-6
                 [:span "Invite sent. They'll be added automatically when they sign in."]])
              [:section.mb-8
               [:h2.text-xl.font-semibold.mb-4 "Team members"]
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
              (when (seq pending-invites)
                [:section.mb-8
                 [:h2.text-xl.font-semibold.mb-4 "Pending invites"]
                 [:div.flex.flex-col.gap-2
                  (for [inv (sort-by :invite/email pending-invites)]
                    [:div.ss-card.p-3.flex.items-center.justify-between
                     [:span.text-sm (:invite/email inv)]
                     [:span.badge.badge-outline.badge-sm "Awaiting sign-in"]])]])
              [:section
               [:h2.text-xl.font-semibold.mb-1 "Add a team member"]
               [:p.text-sm.opacity-60.mb-4 "Enter their email. If they already have an account they'll be added immediately; otherwise they'll join automatically when they sign in."]
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
                (when-let [e (:other-tenant add-errors)]
                  [:p.text-error.mt-2 e])]]))

(defn sport-templates
  "Sport template selection page."
  [all selected & [{:keys [custom-errors custom-data]
                    :or {custom-errors {} custom-data {}}}]]
  (let [platform (filter #(not= false (:sport-template/is-template %)) all)
        custom (filter #(false? (:sport-template/is-template %)) all)]
    (shared/doc "Sports — Sports Manager"
                {:active :sports}
                [:h2.text-2xl.mb-2 "Sports"]
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
