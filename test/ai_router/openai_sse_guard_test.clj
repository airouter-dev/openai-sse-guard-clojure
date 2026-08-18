(ns ai-router.openai-sse-guard-test
  (:require [ai-router.openai-sse-guard :as guard]
            [clojure.test :refer [deftest is testing]]))

(defn- bytes
  [s]
  (.getBytes ^String s "UTF-8"))

(deftest observes-chat-completions-and-done
  (let [snapshot (guard/observe ["data: {\"id\":\"chatcmpl_1\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                                "data: [DONE]\n\n"])]
    (is (= :chat-completions (:protocol-hint snapshot)))
    (is (= :done (:termination snapshot)))
    (is (:has-output? snapshot))
    (is (:saw-terminal-event? snapshot))
    (is (= 2 (:event-count snapshot)))))

(deftest handles-crlf-and-utf8-split-across-byte-chunks
  (let [first (bytes "data: {\"choices\":[{\"delta\":{\"content\":\"你")
        second (bytes "好\"}}]}\r\n\r\n")
        done (bytes "data: [DONE]\r\n\r\n")
        chunks [(take 17 first) (drop 17 first) second done]
        snapshot (guard/observe chunks)]
    (is (= :chat-completions (:protocol-hint snapshot)))
    (is (= :done (:termination snapshot)))
    (is (= 2 (:event-count snapshot)))))

(deftest handles-responses-terminal-events
  (testing "completed"
    (let [snapshot (guard/observe ["event: response.created\ndata: {\"type\":\"response.created\"}\n\n"
                                  "event: response.completed\ndata: {\"type\":\"response.completed\"}\n\n"])]
      (is (= :responses (:protocol-hint snapshot)))
      (is (= :done (:termination snapshot)))))
  (testing "incomplete"
    (let [snapshot (guard/observe ["event: response.incomplete\ndata: {\"type\":\"response.incomplete\"}\n\n"])]
      (is (= :responses (:protocol-hint snapshot)))
      (is (= :incomplete (:termination snapshot))))))

(deftest captures-only-bounded-error-identifier
  (let [snapshot (guard/observe ["event: error\ndata: {\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"do not retain this prose\"}}\n\n"])]
    (is (= :error (:termination snapshot)))
    (is (= "rate_limit_exceeded" (:error-code snapshot)))
    (is (not (some #(= "do not retain this prose" %) (vals snapshot))))))

(deftest incomplete-stream-is-conservative
  (let [observer (guard/feed (guard/new-observer) "data: {\"choices\":[{}]}\n")
        snapshot (guard/snapshot (guard/finish observer))]
    (is (= :unexpected-eof (:termination snapshot)))
    (is (:has-output? snapshot))
    (is (false? (:saw-terminal-event? snapshot)))))

(deftest malformed-utf8-is-rejected
  (let [snapshot (guard/snapshot
                  (guard/finish
                   (guard/feed-bytes (guard/new-observer)
                                     (byte-array [(unchecked-byte 100)
                                                  (unchecked-byte 97)
                                                  (unchecked-byte 116)
                                                  (unchecked-byte 97)
                                                  (unchecked-byte 58)
                                                  (unchecked-byte 32)
                                                  (unchecked-byte 0xC3)]))))]
    (is (= :error (:termination snapshot)))
    (is (= 1 (:malformed-event-count snapshot)))))

(deftest frame-and-event-limits-fail-closed
  (testing "frame limit"
    (let [snapshot (guard/observe ["data: 123456789\n"] {:max-frame-bytes 256})]
      ;; The package clamps the minimum to 256, so this remains an incomplete
      ;; but bounded stream rather than silently lowering the safety floor.
      (is (= :unexpected-eof (:termination snapshot)))))
  (testing "event limit"
    (let [chunks (repeat 3 "data: x\n\n")
          snapshot (guard/observe chunks {:max-events 2})]
      (is (= :error (:termination snapshot)))
      (is (= 1 (:malformed-event-count snapshot))))))

(deftest empty-comment-and-multiple-data-lines
  (let [snapshot (guard/observe [": keep-alive\ndata: first\ndata: second\n\n"])]
    (is (= :unexpected-eof (:termination snapshot)))
    (is (= 1 (:event-count snapshot)))
    (is (:has-output? snapshot))))

(deftest immutable-state-can-be-forked
  (let [base (guard/new-observer)
        left (guard/finish (guard/feed base "data: [DONE]\n\n"))
        right (guard/finish (guard/feed base "event: response.completed\n\n"))]
    (is (= :open (:termination base)))
    (is (= :done (:termination (guard/snapshot left))))
    (is (= :responses (:protocol-hint (guard/snapshot right))))))
