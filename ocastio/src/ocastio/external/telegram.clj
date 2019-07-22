(ns ocastio.external.telegram
  (:require
    [ocastio.db         :as db]
    [ocastio.views      :as v]
    [ocastio.pages.vote :as vote]
    [ocastio.html.result :as r]
    [morse.api          :as t]
    [morse.handlers     :as h]
    [morse.polling      :as p]
    [clojure.string     :as str]
    [clojure.edn        :as edn]
    [clojure.core.async :refer [go <!]]))

(def token (slurp "telegram.tkn"))
(defn send-tx! [id & body]
  (t/send-text token id (str/join "" body)))
(defn send-md! [id & body]
  (t/send-text token id {:parse_mode "Markdown"} (str/join "" body)))
(defn bal-link [ballot-id title type]
  (str "[" title "](http://ocastio.uk/" type "/" ballot-id ")"))

(defn parse-bal-cmd [text]
  (let [text      (str/split text #" ")
        ballot-id (edn/read-string (text 1))
        exists    (and (int? ballot-id)
                       (db/ballot-exists? ballot-id))]
    {:text text :ballot-id ballot-id :exists exists}))

(defn ballot-info [ballot-id]
  (let [{:keys [start hours] :as info}
          (db/ballot-info ballot-id)
        num-vote (db/ballot-num-votes ballot-id)
        state    (v/ballot-state start hours)
        is-poll  (db/poll? ballot-id)
        type     (if is-poll "poll" "ballot")
        Type     (str/capitalize type)]
    (into info {:num-vote num-vote :state state :is-poll is-poll :type type :Type Type})))

(defn bal-opts [{:keys [ballot_id is-poll]}]
  ((if is-poll db/bal-pol-options db/bal-law-options) ballot_id))

(defn assoc-results [opts results]
  (let [sort    (partial sort-by :opt_id)
        opts    (sort opts)
        results (sort results)]
    (map into opts results)))

(defn cmd-start [{{:keys [id]} :chat}]
  (send-tx! id "Type /help for options."))

(defn cmd-help [{{:keys [id]} :chat}]
  (send-md! id "Please register on [Ocastio](http://ocastio.uk) to use this bot."
              "\n/auth email password\n  link Telegram and Ocastio accounts"
              "\n/info ballot-id\n  ballot/poll info"
              "\n/vote ballot-id 0-n ...\n  vote on ballots/polls. 0-n is the range, with 0-1 for approval voting."))

(defn cmd-auth! [{{:keys [id]}       :chat
                  {:keys [username]} :from
                  text               :text}]
  (let [text  (str/split text #" ")
        email (text 1)
        pass  (text 2)]
    (if (db/correct-pass? email pass)
      (do
        (db/set-user-contact! email username)
        (send-tx! id "Authenticated."))
      (send-tx! id "Unable to authenticate."))))

(defn make-option-item [{:keys [state preresult]}
                        {:keys [text title approval sum]}
                        i]
  (let [can-show (or preresult (= state :complete))
        approval (int (* approval 100))
        approval (format " %,3d%%" approval)
        approval (if can-show approval "")
        sum      (format " %,3d" sum)
        sum      (if can-show sum)]
    (str "`" (inc i) "." approval sum " `" text title)))

(defn cmd-info [{{:keys [id]} :chat
                 text         :text}]
  (let [{:keys [text ballot-id exists]}
          (parse-bal-cmd text)]
    (if exists
      (let [{:keys [title desc start hours state
                    num-vote is-poll type Type] :as bal-info}
              (ballot-info ballot-id)
            options     (bal-opts bal-info)
            options     (assoc-results options (r/ballot-results ballot-id))
            options     (map (partial make-option-item bal-info) options (range))
            options     (str/join "\n" options)
            method-info (str/join "" (drop 1 (v/make-method-info bal-info)))
            info-msg    (str "(" (bal-link ballot-id (name state) type) ") "
                             "*" Type " " ballot-id " | " num-vote " ✍️ | " title "*"
                             "\n" method-info "\n_" desc "_\n" options)]
        (send-md! id info-msg))
      (send-tx! id "Ballot not found."))))

(defn test-vote->reason [test-vote]
  ({:auth "all okay"
    :noauth "unauthorised"
    :complete "it's closed"
    :future "it has not yet begun"}
      test-vote))

(defn cmd-vote! [{{:keys [id]}       :chat
                  {:keys [username]} :from
                  text               :text}]
  (let [{:keys [text ballot-id exists]}
          (parse-bal-cmd text)]
    (if exists
      (let [{:keys [title desc type is-poll start hours sco_range] :as bal-info}
              (ballot-info ballot-id)
            options   (map :opt_id (bal-opts bal-info))
            choices   (map edn/read-string (drop 2 text))
            is-valid  (= (count options) (count choices))
            is-valid  (and is-valid (every? int? choices))
            is-valid  (and is-valid (every? #(< % sco_range) choices))
            user-id   (db/contact->id username)
            test-vote (vote/test-vote bal-info user-id)
            reason    (test-vote->reason test-vote)
            state     (v/ballot-state start hours)]
        (if is-valid
          (if (= test-vote :authed)
            (let [choices (map hash-map options choices)
                  choices (reduce into choices)]
              (db/vote-new! (db/contact->id username) ballot-id choices)
              (send-md! id "Vote cast on " (bal-link ballot-id title type) "!"))
            (send-tx! id "You are unable to vote on this " type ": " reason "."))
          (send-tx! id "Invalid options.")))
      (send-tx! id "Ballot not found."))))

(h/defhandler telegram-handler
  (h/command-fn "start" cmd-start)
  (h/command-fn "help"  cmd-help)
  (h/command-fn "auth"  cmd-auth!)
  (h/command-fn "info"  cmd-info)
  (h/command-fn "vote"  cmd-vote!))

(defn start [] (go (<! (p/start token telegram-handler))))