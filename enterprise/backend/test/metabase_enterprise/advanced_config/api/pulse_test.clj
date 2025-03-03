(ns metabase-enterprise.advanced-config.api.pulse-test
  (:require [clojure.test :refer :all]
            [metabase.models :refer [Card]]
            [metabase.public-settings.premium-features-test :as premium-features-test]
            [metabase.test :as mt]
            [metabase.util :as u]))

(deftest test-pulse-endpoint-should-respect-email-domain-allow-list-test
  (testing "POST /api/pulse/test"
    (mt/with-temp Card [card {:dataset_query (mt/mbql-query venues)}]
      (letfn [(send! [expected-status-code]
                (let [pulse-name (mt/random-name)]
                  (mt/with-fake-inbox
                    {:response   (mt/user-http-request
                                  :rasta :post expected-status-code "pulse/test"
                                  {:name          pulse-name
                                   :cards         [{:id                (u/the-id card)
                                                    :include_csv       false
                                                    :include_xls       false
                                                    :dashboard_card_id nil}]
                                   :channels      [{:enabled       true
                                                    :channel_type  "email"
                                                    :schedule_type "daily"
                                                    :schedule_hour 12
                                                    :schedule_day  nil
                                                    :details       {:emails ["test@metabase.com"]}}]
                                   :skip_if_empty false})
                     :recipients (set (keys (mt/regex-email-bodies (re-pattern pulse-name))))})))]
        (testing "allowed email -- should pass"
          (mt/with-temporary-setting-values [subscription-allowed-domains "metabase.com"]
            (premium-features-test/with-premium-features #{:advanced-config}
              (let [{:keys [response recipients]} (send! 200)]
                (is (= {:ok true}
                       response))
                (is (contains? recipients "test@metabase.com"))))
            (testing "No :advanced-config token"
              (premium-features-test/with-premium-features #{}
                (let [{:keys [response recipients]} (send! 200)]
                  (is (= {:ok true}
                         response))
                  (is (contains? recipients "test@metabase.com")))))))
        (testing "disallowed email"
          (mt/with-temporary-setting-values [subscription-allowed-domains "example.com"]
            (testing "should fail when :advanced-config is enabled"
              (premium-features-test/with-premium-features #{:advanced-config}
                (let [{:keys [response recipients]} (send! 403)]
                  (is (= "You cannot create new subscriptions for the domain \"metabase.com\". Allowed domains are: example.com"
                         (:message response)))
                  (is (not (contains? recipients "test@metabase.com"))))))
            (testing "No :advanced-config token -- should still pass"
              (premium-features-test/with-premium-features #{}
                (let [{:keys [response recipients]} (send! 200)]
                  (is (= {:ok true}
                         response))
                  (is (contains? recipients "test@metabase.com")))))))))))
