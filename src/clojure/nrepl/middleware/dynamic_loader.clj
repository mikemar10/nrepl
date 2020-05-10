(ns nrepl.middleware.dynamic-loader
  "Support the ability to interactively update the middleware of the *running*
  nREPL server. This can be used by tools to configure an existing instance of
  an environment after connection.

  When combined with the sideloader, this could be used to inject middleware
  that are unknown to the server prior to connection."
  {:author "Shen Tian", :added "0.8"}
  (:require [clojure.string :as str]
            [nrepl.middleware :refer [linearize-middleware-stack set-descriptor!]]
            [nrepl.middleware.session :as middleware.session]
            [nrepl.misc :as misc :refer [response-for with-session-classloader]]
            [nrepl.transport :as t]))

(def ^:dynamic *state* nil)

(defn unknown-op
  "Sends an :unknown-op :error for the given message."
  [{:keys [op transport] :as msg}]
  (t/send transport (response-for msg :status #{:error :unknown-op :done} :op op)))

(defn- update-stack!
  [session middleware]
  (with-session-classloader session
    (try
      (let [stack (->> middleware
                       (map (fn [middleware-str-or-var]
                              (if (var? middleware-str-or-var)
                                middleware-str-or-var
                                (-> middleware-str-or-var
                                    (str/replace "#'" "")
                                    symbol
                                    misc/requiring-resolve))))
                       linearize-middleware-stack)]
        (reset! *state* {:handler ((apply comp (reverse stack)) unknown-op)
                         :stack   stack}))
      (catch Throwable t
        {:error        t
         :before-stack (map str (:stack @*state*))}))))

(defn wrap-dynamic-loader
  [h]
  (fn [{:keys [op transport session middleware] :as msg}]
    (case op
      "add-middleware"
      (do
        (let [{:keys [error alt-cl? before-stack]}
              (update-stack! session (concat middleware (:stack @*state*)))]
          (when error
            (t/send transport (response-for msg {:error        (str error)
                                                 :sideloading  (str alt-cl?)
                                                 :before-stack before-stack}))))
        (t/send transport (response-for msg {:status :done})))

      "swap-middleware"
      (do
        (update-stack! session middleware)
        (when transport
          (t/send transport (response-for msg :middleware (mapv str (:stack @*state*))))
          (t/send transport (response-for msg {:status :done}))))

      "ls-middleware"
      (do
        (t/send transport (response-for msg :middleware (mapv str (:stack @*state*))))
        (t/send transport (response-for msg {:status :done})))

      (h msg))))

(set-descriptor! #'wrap-dynamic-loader
                 {:requires #{#'middleware.session/session}
                  :expects  #{}
                  :handles  {"add-middleware"
                             {:doc     "Adding some middleware, dynamically"
                              :require {"middleware" "a list of middleware"}
                              :returns {"status" "done, once done"}}}})
