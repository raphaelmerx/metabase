(ns metabase.models.pulse-channel
  (:require [cheshire.generate :refer [add-encoder encode-map]]
            [clojure.set :as set]
            [medley.core :as m]
            [metabase.models.interface :as i]
            [metabase.models.pulse-channel-recipient :refer [PulseChannelRecipient]]
            [metabase.models.user :as user :refer [User]]
            [metabase.plugins.classloader :as classloader]
            [metabase.util :as u]
            [schema.core :as s]
            [toucan.db :as db]
            [toucan.models :as models]))

;; ## Static Definitions

(def days-of-week
  "Simple `vector` of the days in the week used for reference and lookups.

   NOTE: order is important here!!
         we use the same ordering as the clj-time `day-of-week` function (1 = Monday, 7 = Sunday) except
         that we are 0 based instead."
  [{:id "mon", :name "Mon"},
   {:id "tue", :name "Tue"},
   {:id "wed", :name "Wed"},
   {:id "thu", :name "Thu"},
   {:id "fri", :name "Fri"},
   {:id "sat", :name "Sat"},
   {:id "sun", :name "Sun"}])

(def ^{:arglists '([day])} day-of-week?
  "Is `day` a valid `day-of-week` choice?"
  (partial contains? (set (map :id days-of-week))))

(defn hour-of-day?
  "Is `hour` is a valid hour of the day (24 hour)?"
  [hour]
  (and (integer? hour) (<= 0 hour 23)))

(def ^:private schedule-frames
  "Set of possible schedule-frames allow for a PulseChannel."
  #{:first :mid :last})

(defn schedule-frame?
  "Is `frame` a valid schedule frame?"
  [frame]
  (contains? schedule-frames frame))

(def ^:private schedule-types
  "Set of the possible schedule-types allowed for a PulseChannel."
  #{:hourly :daily :weekly :monthly})

(defn schedule-type?
  "Is `schedule-type` a valid PulseChannel schedule type?"
  [schedule-type]
  (contains? schedule-types schedule-type))

(defn valid-schedule?
  "Is this combination of scheduling choices valid?"
  [schedule-type schedule-hour schedule-day schedule-frame]
  (or
    ;; hourly schedule does not care about other inputs
    (= schedule-type :hourly)
    ;; daily schedule requires a valid `hour`
    (and (= schedule-type :daily)
         (hour-of-day? schedule-hour))
    ;; weekly schedule requires a valid `hour` and `day`
    (and (= schedule-type :weekly)
         (hour-of-day? schedule-hour)
         (day-of-week? schedule-day))
    ;; monthly schedule requires a valid `hour` and `frame`.  also a `day` if frame = first or last
    (and (= schedule-type :monthly)
         (schedule-frame? schedule-frame)
         (hour-of-day? schedule-hour)
         (or (contains? #{:first :last} schedule-frame)
             (and (= :mid schedule-frame)
                  (nil? schedule-day))))))

(def channel-types
  "Map which contains the definitions for each type of pulse channel we allow.  Each key is a channel type with a map
   which contains any other relevant information for defining the channel.  E.g.

   {:email {:name \"Email\", :recipients? true}
    :slack {:name \"Slack\", :recipients? false}}"
  {:email {:type              "email"
           :name              "Email"
           :allows_recipients true
           :recipients        ["user" "email"]
           :schedules         [:hourly :daily :weekly :monthly]}
   :slack {:type              "slack"
           :name              "Slack"
           :allows_recipients false
           :schedules         [:hourly :daily :weekly :monthly]
           :fields            [{:name        "channel"
                                :type        "select"
                                :displayName "Post to"
                                :options     []
                                :required    true}]}})

(defn channel-type?
  "Is `channel-type` a valid value as a channel type? :tv:"
  [channel-type]
  (contains? (set (keys channel-types)) channel-type))

(defn supports-recipients?
  "Does given `channel` type support a list of recipients? :tv:"
  [channel]
  (boolean (:allows_recipients (get channel-types channel))))


;; ## Entity

(models/defmodel PulseChannel :pulse_channel)

(defn ^:hydrate recipients
  "Return the `PulseChannelRecipients` associated with this `pulse-channel`."
  [{pulse-channel-id :id, {:keys [emails]} :details}]
  (concat
   (for [email emails]
     {:email email})
   (for [user (db/query
               {:select    [:u.id :u.email :u.first_name :u.last_name]
                :from      [[User :u]]
                :left-join [[PulseChannelRecipient :pcr] [:= :u.id :pcr.user_id]]
                :where     [:and
                            [:= :pcr.pulse_channel_id pulse-channel-id]
                            [:= :u.is_active true]]
                :order-by [[:u.id :asc]]})]
     (user/add-common-name user))))

(defn- pre-delete [pulse-channel]
  ;; Call [[metabase.models.pulse/will-delete-channel]] to let it know we're about to delete a PulseChannel; that
  ;; function will decide whether or not to automatically archive the Pulse as well.
  (classloader/require 'metabase.models.pulse)
  ((resolve 'metabase.models.pulse/will-delete-channel) pulse-channel))

;; we want to load this at the top level so the Setting the namespace defines gets loaded
(def ^:private ^{:arglists '([email-addresses])} validate-email-domains*
  (or (u/ignore-exceptions
        (classloader/require 'metabase-enterprise.advanced-config.models.pulse-channel)
        (resolve 'metabase-enterprise.advanced-config.models.pulse-channel/validate-email-domains))
      (constantly nil)))

(defn validate-email-domains
  "For channels that are being sent to raw email addresses: check that the domains in the emails are allowed by
  the [[metabase-enterprise.advanced-config.models.pulse-channel/subscription-allowed-domains]] Setting, if set. This
  will no-op if `subscription-allowed-domains` is unset or if we do not have a premium token with the
  `:advanced-config` feature."
  [{{:keys [emails]} :details, :as pulse-channel}]
  (u/prog1 pulse-channel
    (validate-email-domains* emails)))

(u/strict-extend (class PulseChannel)
  models/IModel
  (merge
   models/IModelDefaults
   {:hydration-keys (constantly [:pulse_channel])
    :types          (constantly {:details :json, :channel_type :keyword, :schedule_type :keyword, :schedule_frame :keyword})
    :properties     (constantly {:timestamped? true})
    :pre-delete     pre-delete
    :pre-insert     validate-email-domains
    :pre-update     validate-email-domains})

  i/IObjectPermissions
  (merge
   i/IObjectPermissionsDefaults
   {:can-read?  (constantly true)
    :can-write? i/superuser?}))

(defn will-delete-recipient
  "This function is called by [[metabase.models.pulse-channel-recipient/pre-delete]] when a `PulseChannelRecipient` is
  about to be deleted. Deletes `PulseChannel` if the recipient being deleted is its last recipient. (This only applies
  to PulseChannels with User subscriptions; Slack PulseChannels and ones with email address subscriptions are not
  automatically deleted.)"
  [{channel-id :pulse_channel_id, pulse-channel-recipient-id :id}]
  (let [other-recipients-count (db/count PulseChannelRecipient :pulse_channel_id channel-id, :id [:not= pulse-channel-recipient-id])
        last-recipient?        (zero? other-recipients-count)]
    (when last-recipient?
      ;; make sure this channel doesn't have any email-address (non-User) recipients.
      (let [details              (db/select-one-field :details PulseChannel :id channel-id)
            has-email-addresses? (seq (:emails details))]
        (when-not has-email-addresses?
          (db/delete! PulseChannel :id channel-id))))))


;; ## Persistence Functions

(s/defn retrieve-scheduled-channels
  "Fetch all `PulseChannels` that are scheduled to run at a given time described by `hour`, `weekday`, `monthday`, and
  `monthweek`.

  Examples:

    (retrieve-scheduled-channels 14 \"mon\" :first :first)  -  2pm on the first Monday of the month
    (retrieve-scheduled-channels 8 \"wed\" :other :last)    -  8am on Wednesday of the last week of the month

  Based on the given input the appropriate `PulseChannels` are returned:

  *  `hourly` scheduled channels are always included.
  *  `daily` scheduled channels are included if the `hour` matches.
  *  `weekly` scheduled channels are included if the `weekday` & `hour` match.
  *  `monthly` scheduled channels are included if the `monthday`, `monthweek`, `weekday`, & `hour` all match."
  [hour      :- (s/maybe s/Int)
   weekday   :- (s/maybe (s/pred day-of-week?))
   monthday  :-  (s/enum :first :last :mid :other)
   monthweek :- (s/enum :first :last :other)]
  (let [schedule-frame              (cond
                                      (= :mid monthday)    "mid"
                                      (= :first monthweek) "first"
                                      (= :last monthweek)  "last"
                                      :else                "invalid")
        monthly-schedule-day-or-nil (when (= :other monthday)
                                      weekday)]
    (db/select [PulseChannel :id :pulse_id :schedule_type :channel_type]
      {:where [:and [:= :enabled true]
               [:or [:= :schedule_type "hourly"]
                [:and [:= :schedule_type "daily"]
                 [:= :schedule_hour hour]]
                [:and [:= :schedule_type "weekly"]
                 [:= :schedule_hour hour]
                 [:= :schedule_day weekday]]
                [:and [:= :schedule_type "monthly"]
                 [:= :schedule_hour hour]
                 [:= :schedule_frame schedule-frame]
                 [:or [:= :schedule_day weekday]
                  ;; this is here specifically to allow for cases where day doesn't have to match
                  [:= :schedule_day monthly-schedule-day-or-nil]]]]]})))


(defn update-recipients!
  "Update the `PulseChannelRecipients` for `pulse-CHANNEL`.
  `user-ids` should be a definitive collection of *all* IDs of users who should receive the pulse.

  *  If an ID in `user-ids` has no corresponding existing `PulseChannelRecipients` object, one will be created.
  *  If an existing `PulseChannelRecipients` has no corresponding ID in USER-IDs, it will be deleted."
  [id user-ids]
  {:pre [(integer? id)
         (coll? user-ids)
         (every? integer? user-ids)]}
  (let [recipients-old (set (db/select-field :user_id PulseChannelRecipient, :pulse_channel_id id))
        recipients-new (set user-ids)
        recipients+    (set/difference recipients-new recipients-old)
        recipients-    (set/difference recipients-old recipients-new)]
    (when (seq recipients+)
      (let [vs (map #(assoc {:pulse_channel_id id} :user_id %) recipients+)]
        (db/insert-many! PulseChannelRecipient vs)))
    (when (seq recipients-)
      (db/simple-delete! PulseChannelRecipient
        :pulse_channel_id id
        :user_id          [:in recipients-]))))


(defn update-pulse-channel!
  "Updates an existing `PulseChannel` along with all related data associated with the channel such as
  `PulseChannelRecipients`."
  [{:keys [id channel_type enabled details recipients schedule_type schedule_day schedule_hour schedule_frame]
    :or   {details          {}
           recipients       []}}]
  {:pre [(integer? id)
         (channel-type? channel_type)
         (m/boolean? enabled)
         (schedule-type? schedule_type)
         (valid-schedule? schedule_type schedule_hour schedule_day schedule_frame)
         (coll? recipients)
         (every? map? recipients)]}
  (let [recipients-by-type (group-by integer? (filter identity (map #(or (:id %) (:email %)) recipients)))]
    (db/update! PulseChannel id
      :details        (cond-> details
                        (supports-recipients? channel_type) (assoc :emails (get recipients-by-type false)))
      :enabled        enabled
      :schedule_type  schedule_type
      :schedule_hour  (when (not= schedule_type :hourly)
                        schedule_hour)
      :schedule_day   (when (contains? #{:weekly :monthly} schedule_type)
                        schedule_day)
      :schedule_frame (when (= schedule_type :monthly)
                        schedule_frame))
    (when (supports-recipients? channel_type)
      (update-recipients! id (or (get recipients-by-type true) [])))))


(defn create-pulse-channel!
  "Create a new `PulseChannel` along with all related data associated with the channel such as
  `PulseChannelRecipients`."
  [{:keys [channel_type details enabled pulse_id recipients schedule_type schedule_day schedule_hour schedule_frame]
    :or   {details          {}
           recipients       []}}]
  {:pre [(channel-type? channel_type)
         (integer? pulse_id)
         (boolean? enabled)
         (schedule-type? schedule_type)
         (valid-schedule? schedule_type schedule_hour schedule_day schedule_frame)
         (coll? recipients)
         (every? map? recipients)]}
  (let [recipients-by-type (group-by integer? (filter identity (map #(or (:id %) (:email %)) recipients)))
        {:keys [id]} (db/insert! PulseChannel
                       :pulse_id       pulse_id
                       :channel_type   channel_type
                       :details        (cond-> details
                                         (supports-recipients? channel_type) (assoc :emails (get recipients-by-type false)))
                       :enabled        enabled
                       :schedule_type  schedule_type
                       :schedule_hour  (when (not= schedule_type :hourly)
                                         schedule_hour)
                       :schedule_day   (when (contains? #{:weekly :monthly} schedule_type)
                                         schedule_day)
                       :schedule_frame (when (= schedule_type :monthly)
                                         schedule_frame))]
    (when (and (supports-recipients? channel_type) (seq (get recipients-by-type true)))
      (update-recipients! id (get recipients-by-type true)))
    ;; return the id of our newly created channel
    id))


;; don't include `:emails`, we use that purely internally
(add-encoder PulseChannelInstance (fn [pulse-channel json-generator]
                                    (encode-map (m/dissoc-in pulse-channel [:details :emails])
                                                json-generator)))
