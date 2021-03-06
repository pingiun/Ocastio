(ns ocastio.pages.vote
  (:require
    [ocastio.views :as v]
    [ocastio.db :as db]
    [ocastio.html.result :as r]
    [clojure.string :as str]
    [hiccup.page :as h]
    [clj-time.core :as t]
    [clj-time.format :as f]
    [clj-time.coerce :as tc]
    [ring.util.anti-forgery :as util])
  (:import (java.time Instant ZoneId LocalDateTime format.DateTimeFormatter))) ;TODO phase-out in preference of clj-time

;Limits the number of options a voter can pick
(def validate-limit-script
  [:script
"const es = (el) => document.querySelectorAll(el);
const e = (el) => es(el)[0];
function ValidateChoice() {
  const current = es('input[type=checkbox]:checked').length;
  const limit = parseInt(e('form').getAttribute('data-limit'));
  e('#under_warning').style.display = 'none';
  e('#limit_warning').style.display = 'none';
  if (!current) e('#under_warning').style.display = '';
  if (current > limit) e('#limit_warning').style.display = '';
  e('input[type=submit]').disabled = (!current || current > limit);
}"])


(defn extract-int [s] (Integer. (re-find  #"\d+" s))) ;TODO commonise
(def select-values (comp vals select-keys))

(defn select-opts [para]
  "{:optNum 'opt-val',} -> {opt-num opt-val,}"
  (->> para
    (map #(vector (name (% 0)) (% 1)))
    (filter #(str/starts-with? (% 0) "opt"))
    (map #(vector (extract-int (% 0)) (% 1)))
    (into {})))

(defn test-vote [bal-info user-id]
  (let [{:keys [ballot_id start hours org_id]}
          bal-info
        state (v/ballot-state bal-info)]
    (if (= state :ongoing)
      (if
        (if org_id
          (db/can-poll-vote? org_id user-id)
          (db/can-ballot-vote? ballot_id user-id))
        :authed
        :noauth)
      state)))

(defn normalise-vals [hmap sco-range]
"Forces scores in {opt score} into the accepted range"
  (zipmap
    (map key hmap)
    (map
      #(if (<= 0 % sco-range)
          %
          (if neg? 0 sco-range))
      (map #(-> % val Integer.) hmap))))

(defn all-opts-if-agree [ballot-id method-id {choice :opt-1} opts]
  (if (and (<= 6 method-id 7)
           choice)
    (zipmap
      (map :opt_id (db/bal-pol-options ballot-id))
      (repeat choice))
    opts))

; TODO check options are of ballot
(defn vote! [{{:keys [ballot_id] :as para} :params
              {:keys [email] :as sess}     :session}]
  (let [user-id     (db/email->id (:email sess))
        is-poll     (db/poll? ballot_id)
        type        (if is-poll "poll" "ballot")
        {:keys [method_id sco_range] :as bal-info}
          (db/ballot-basic-info ballot_id)
        test-vote   (test-vote bal-info user-id)
        opts        (select-opts para)
        opts-made   (seq opts)
        is-abstain  (:abstain para)
        {:keys [is_score]}
          (db/method-info method_id)
        is-too-many (if is_score false (< sco_range (count opts)))
        opts        (if is_score (normalise-vals opts sco_range) opts)
        opts        (all-opts-if-agree ballot_id method_id para opts)]
    (if (and (= test-vote :authed)
             (or opts-made is-abstain)
             (not is-too-many))
      (db/vote-new! user-id ballot_id opts is-abstain))
    {:redir (str "/" type "/" ballot_id) :sess sess}))

(defn make-check-input [{:keys [opt_id text] :as option}]
  (def opt (str "opt" opt_id))
  [:balopt
    [:input {:type "checkbox" :value 1 :name opt :id opt :onchange "ValidateChoice()"}]
    [:label {:for opt}]
    [:span (v/make-option-text option)]])

(defn make-score-radio [value opt checked?]
  (def id (str \o opt value))
  [:span
    [:input {:type "radio" :value value :name opt :id id :checked checked?}]
    [:label {:for id} value]])
(defn make-score-input [score-range {:keys [opt_id text] :as option}]
  (let [opt (str "opt" opt_id)
        half (quot score-range 2)]
    [:balopt
      (map #(make-score-radio % opt (= % half))
           (range 0 (inc score-range)))
      [:span (v/make-option-text option)]]))

(defn make-mass-list [option]
  [:balopt [:span (v/make-option-text option)]])

(defn make-option-form [options ballot-id method-id range]
  (let [{:keys [is_score]}
          (db/method-info method-id)
        is-mass (#{6 7} method-id)] ;TODO pull from db
    [:form#vote {:action (str "/vote/" ballot-id) :method "POST"
                 :data-stv (= method-id 5) :data-limit range}
      (util/anti-forgery-field)
      (if-not is_score validate-limit-script)
      [:br]
      (let [fchk    make-check-input
            fsco    (partial make-score-input range)
            frnk    (partial make-score-input (count options))
            flst    make-mass-list
            per-opt ({1 fchk 2 fsco 3 fchk 4 fsco 5 frnk 6 flst 7 flst} method-id)]
        (map per-opt options))
      (if is-mass
        (({6 make-check-input 7 (partial make-score-input range)} method-id)
          {:opt_id -1 :text "I agree with all options."}))
      (if-not (or is_score is-mass)
        [:p [:b#limit_warning {:style "display: none"} "You can only pick up to " range " options."]
            [:b#under_warning "You must pick at least one option, or abstain."]])
      [:input {:type "submit" :value "Cast vote"
               :disabled (if-not (or is_score is-mass) "")}]
      [:input {:type "submit" :name "abstain" :value "Abstain"}]]))

(defn make-option-info [options]
  [:ol (map #(vector :li (v/make-option-text %)) options)])

(defn page [{{ballot-id :ballot_id} :params
             {:keys [email]}        :session} compose poll?]
  (let [ballot-id (Integer. ballot-id)
        exists    (db/ballot-exists? ballot-id)]
    (if (not exists)
      (compose "Ballot doesn't exist" nil [:h2 "Ballot doesn't exist."])
      (let [{:keys [title org_id method_id desc hours start
                    sco_range preresult] :as info}
            (db/ballot-info ballot-id)

          org-name  (:name (db/org-basic-info org_id))
          options   (if poll? (db/bal-pol-options ballot-id) (db/bal-law-options ballot-id))
          num-votes (db/ballot-num-votes ballot-id)
          remaining (v/ballot-time-status info)

          user-id   (db/email->id email)
          can-vote? (if poll?
            (db/can-poll-vote? org_id user-id)
            (db/can-ballot-vote? ballot-id user-id))

          con-id    (db/bal->con ballot-id)
          con-name  (:title (db/con-info con-id))

          admin?    (db/org-admin? org_id email)
          exec?     (db/con-exec? con-id email)
          state     (v/ballot-state info)
          results?  (or (= state :complete) preresult)
          {:keys [complete? ongoing? future?]}
            (v/state-to-key state)

          Type      (if poll? "Poll" "Ballot")
          type      (str/lower-case Type)]
      (compose (str title " | " Type) (if (= method_id 5) (h/include-js "/js/stv.js"))
        (if poll?
          [:navinfo "Conducted by "  [:a {:href (str "/org/" org_id)} org-name] "."]
          [:navinfo "Conducted for " [:a {:href (str "/con/" con-id)} con-name] "."])
        [:h2 title [:grey " | " Type]]
        [:quote [:pre (if (= desc "") "No description." desc)]]
        [:p (if-not complete? [:b remaining ". "])
            "From " [:b (v/ballot-time-str info)] " for " [:b hours "h"] ". "
            (v/make-method-info info) "."]
        (if (not complete?)
          [:div
            (if (or can-vote? (not results?)) [:h3 "Options"])
            (if (and future? can-vote?)
              [:div
                [:p.admin "You will be eligible to vote."]
                (make-option-info options)])
            (if (and (not can-vote?) (not results?))
              (make-option-info options))
            (if (and ongoing? can-vote?)
              [:div
                [:p.admin "You are eligible to vote."]
                (make-option-form options ballot-id method_id sco_range)])])
        (if (and (not future?) results?)
          [:div
            [:h3 "Results"]
            [:p num-votes " " (v/plu "vote" num-votes) " total, " (db/num-abstain ballot-id) " abstain."]
            (r/ballot-results-html ballot-id)])
        (if (if poll? admin? exec?)
          (v/make-del-button (str "/bal/del/" ballot-id) type)))))))


(defn make-form-new [type Type id option-form]
  (let [date-now  (f/unparse (f/formatter "yyyy-MM-dd") (t/now))
        time-now  (f/unparse (f/formatter "HH:mm") (t/now))]
    [:form {:action (str "/" type "/new/" id) :method "POST"}
        (util/anti-forgery-field)
        [:input {:type "text" :name "title" :placeholder (str Type " title")
                 :minlength 8 :maxlength 64 :required true}]
        [:textarea {:name "desc" :placeholder "Description" :maxlength 256}]
        [:select {:name "method_id" :onchange "UpdateBallotDOM(this)"}
          (map v/make-method-option (db/vote-methods))]
        option-form
        [:p#num_win.inline
          [:span "Number of winning laws/options: "]
          [:input#num_win {:name "num_win" :type "number" :value 1 :min 1 :max 16}]]
        [:p#majority.inline
          [:span "Majority beyond "]
          [:input#majority {:name "majority" :type "number" :value 50 :min 1 :max 100}]
          [:span "%"]]
        [:p#range.inline
          [:span "Score range between 0 and "]
          [:input#range {:name "sco_range" :type "number" :value 4 :min 2 :max 16 :step 2}]]
        [:p#limit.inline
          [:span "Allow voter to vote on up to "]
          [:input#limit_num {:name "limit_num" :type "number" :value 1 :min 1}]
          [:span " options"]]
        [:p "Dates and times are UTC."]
        [:p.inline "Starting at"
          [:input {:type "date" :name "date" :value date-now}]
          [:input {:type "time" :name "time" :value time-now}]]
        [:p "Duration (days: 0-9, hours: 1-24):"]
        [:p.inline
          [:input {:type "range" :name "days"  :id "day"  :value 0 :min 0 :max 9}]
          [:span#days "0 days"]]
        [:p.inline
          [:input {:type "range" :name "hours" :id "hour" :value 1 :min 1 :max 24}]
          [:span#hours "1 hours"]]
        [:p.inline
          [:input {:type "checkbox" :name "preresult" :id "preresult"}
          [:label {:for "preresult"}]
          [:span "Permanently show results"]]]
        [:input {:type "submit"   :value "Post ballot"}]]))


(defn process-new!-post
  [{:keys [title desc date time days hours method_id
           num_win sco_range majority limit_num preresult]}
   {email :email}]
  "Browser [para sess] -> {:setting val}"
  (let [sco_range (Integer. sco_range)
        sco_range (#(if (even? %) % (dec %)) sco_range)
        sco_range (if (< 2 sco_range 16) sco_range 4)
        majority  (Integer. majority)
        majority  (if (< 1 majority 100) majority 50)
        limit_num (Integer. limit_num)
        limit_num (if (< 1 limit_num) limit_num 1)
        {:keys [is_score]}
          (db/method-info method_id)]
    { :title      title
      :desc       desc
      :user_id    (db/email->id email)
      :method_id  (Integer. method_id)
      :num_win    (Integer. num_win)
      :sco_range  (if is_score sco_range limit_num)
      :majority   majority
      :start
        (->> (str date " " time)
          (f/parse (f/formatter "yyy-MM-dd HH:mm"))
          (tc/to-sql-time))
      :hours      (+ (Integer. hours) (* (Integer. days) 24))
      :preresult  (boolean preresult)}))

;TODO is admin, no crash on empty times, >1 options, TODO find more things to do
(defn new-pol! [{{:keys [org-id] :as para} :params sess :session}]
  (let [info    (process-new!-post para sess)
        info    (assoc info :org_id (Integer. org-id))
        options (map second (select-opts para))
        poll-id (db/ballot-new! info)]
    (doseq [text options] (db/bal-opt-new! poll-id nil text))
    {:redir (str "/poll/" poll-id) :sess sess}))

;TODO is exec, no crash on empty times, check ballot doesn't overlap, check laws are all from same con
(defn new-bal! [{{:keys [con-id] :as para} :params
                 sess                      :session}]
  (let [info      (process-new!-post para sess)
        law-pairs (filter #(str/starts-with? (name (first %)) "law") (vec para))
        law-ids   (map #(extract-int (name (first %))) law-pairs)
        ballot-id (db/ballot-new! info)]
    (doseq [id law-ids] (db/bal-opt-new! ballot-id id nil))
    {:redir (str "/ballot/" ballot-id) :sess sess}))


(defn del! [{{ballot-id :ballot-id}   :params
             {:keys [email] :as sess} :session}]
  (let [poll?   (db/poll? ballot-id)
        id      ((if poll? db/poll->org db/bal->con) ballot-id)
        authed? ((if poll? db/org-admin? db/con-exec?) id email)]
  (if authed? (db/bal-del! ballot-id))
  {:redir (str (if poll? "/org/" "/con/") id)
   :sess  sess}))
