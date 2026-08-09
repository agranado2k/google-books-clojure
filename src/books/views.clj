(ns books.views
  "Server-rendered pages (Hiccup). `layout` is the shared page frame —
  header, content area, footer — every page reuses; styling comes from the
  Tailwind-built stylesheet at /css/app.css (see styles/app.css).

  Class strings are literals in this file on purpose: Tailwind scans `src`
  (styles/app.css declares it) and only emits utilities it can read there."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]))

(def ^:private brand "Google Books")

;; ---------------------------------------------------------------------------
;; Shared class strings — named once, so a change of measure or of the eyebrow
;; treatment is one edit rather than a search-and-replace across the page.
;; ---------------------------------------------------------------------------

(defn- classes
  "Joins class-string fragments into one `:class` value."
  [& fragments]
  (str/join " " (remove nil? fragments)))

(def ^:private container
  "The page measure: every band of content lines up on this."
  "mx-auto max-w-5xl px-6")

(def ^:private eyebrow
  "The small caps label that introduces a section. Colour comes per use."
  "text-sm font-medium uppercase tracking-widest")

(def ^:private card-status
  "The smaller caps label at the foot of a roadmap card."
  "text-xs font-medium uppercase tracking-wide")

;; ---------------------------------------------------------------------------
;; The roadmap: content as data, rendered by one function.
;; ---------------------------------------------------------------------------

(def ^:private statuses
  "Roadmap status -> how it reads and how it looks. Colour is derived from the
  status, never repeated per card."
  {:next {:label "Coming next" :class "text-amber-700"}
   :later {:label "Coming later" :class "text-stone-400"}})

(def ^:private roadmap
  [{:title "Search"
    :blurb "Find any book in the Google Books catalog by title, author, or keyword."
    :status :next}
   {:title "Bookmarks"
    :blurb "Save the books you care about and find them again in one place."
    :status :next}
   {:title "Sign-in"
    :blurb "Your bookmarks, tied to you — an account so they follow you around."
    :status :later}])

(defn- roadmap-card [{:keys [title blurb status]}]
  (let [{:keys [label] status-class :class} (statuses status)]
    [:div {:class "rounded-2xl border border-stone-200 bg-white p-6 shadow-sm"}
     [:h3 {:class "font-serif text-xl text-stone-900"} title]
     [:p {:class "mt-2 text-sm leading-relaxed text-stone-600"} blurb]
     [:p {:class (classes "mt-4" card-status status-class)} label]]))

;; ---------------------------------------------------------------------------
;; The shared frame
;; ---------------------------------------------------------------------------

(defn- header []
  [:header {:class "border-b border-stone-200 bg-white/80 backdrop-blur"}
   [:div {:class (classes container "flex items-center justify-between py-4")}
    [:a {:href "/" :class "flex items-center gap-2 text-lg font-semibold tracking-tight text-stone-900"}
     [:span {:class "inline-flex h-8 w-8 items-center justify-center rounded-lg bg-amber-600 font-serif text-base text-white"
             :aria-hidden "true"} "B"]
     brand]
    [:span {:class "rounded-full border border-stone-300 px-3 py-1 text-xs font-medium text-stone-500"}
     "Sign-in coming soon"]]])

(defn- footer []
  [:footer {:class "border-t border-stone-200"}
   [:div {:class (classes container "flex flex-col gap-2 py-8 text-sm text-stone-500 sm:flex-row sm:items-center sm:justify-between")}
    [:p "Built with Clojure. Data from the Google Books API."]
    [:p [:a {:href "/health" :class "underline decoration-stone-300 underline-offset-4 hover:text-stone-700"}
         "Service status"]]]])

(defn layout
  "The shared page frame: <head> with the Tailwind stylesheet, then
  header / content area / footer. `content` is Hiccup for the <main> area."
  [{:keys [title]} & content]
  (str
   (h/html
    (h/raw "<!DOCTYPE html>")
    [:html {:lang "en" :class "h-full"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title title]
      [:link {:rel "stylesheet" :href "/css/app.css"}]]
     [:body {:class "flex min-h-full flex-col bg-stone-50 font-sans text-stone-900 antialiased"}
      (header)
      (into [:main {:class "flex-1"}] content)
      (footer)]])))

(defn landing-page
  "The landing page: what the app does today and what is coming, no more."
  []
  (layout
   {:title (str brand " — search the catalog, bookmark the keepers")}
   [:section {:class (classes container "pb-16 pt-16 sm:pt-24")}
    [:p {:class (classes "mb-4" eyebrow "text-amber-700")}
     "A reading companion, in the making"]
    [:h1 {:class "max-w-2xl font-serif text-4xl leading-tight text-stone-900 sm:text-5xl"}
     "Search the Google Books catalog. Keep the books that matter."]
    [:p {:class "mt-6 max-w-xl text-lg leading-relaxed text-stone-600"}
     "This app will let you explore millions of titles through the Google Books"
     " API and bookmark the ones you want to come back to. It is being built in"
     " the open, one small slice at a time — today the service is up and"
     " serving; the features below are on their way."]]
   [:section {:class (classes container "pb-24")}
    [:h2 {:class (classes "mb-6" eyebrow "text-stone-400")}
     "What's coming"]
    (into [:div {:class "grid gap-6 sm:grid-cols-3"}]
          (map roadmap-card roadmap))]))
