(ns sports-manager.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [sports-manager.routes.core :as routes]))

(deftest root-shows-login-when-logged-out
  (testing "GET / with no session serves the login/signup page"
    (let [resp (routes/handler {:request-method :get :uri "/"})]
      (is (= 200 (:status resp)))
      (is (re-find #"<!DOCTYPE html>" (:body resp)))
      (is (re-find #"Sign in or create an account" (:body resp)))
      (is (re-find #"Continue with Google" (:body resp))))))

(deftest login-alias
  (testing "GET /login serves the same auth page"
    (let [resp (routes/handler {:request-method :get :uri "/login"})]
      (is (= 200 (:status resp)))
      (is (re-find #"Continue with Google" (:body resp))))))

(deftest session-exchange-rejects-bad-token
  (testing "POST /auth/session with an invalid token is unauthorized"
    (let [resp (routes/handler
                {:request-method :post :uri "/auth/session"
                 :headers {"content-type" "application/json"}
                 :body (java.io.ByteArrayInputStream.
                        (.getBytes "{\"token\":\"bogus\"}"))})]
      (is (= 401 (:status resp))))))
