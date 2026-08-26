(ns books.views
  "Server-rendered pages (Hiccup). `layout` is the shared page frame —
  header, content area, footer — every page reuses; styling comes from the
  Tailwind-built stylesheet at /css/app.css (see styles/app.css).

  Class strings are literals in this file on purpose: Tailwind scans `src`
  (styles/app.css declares it) and only emits utilities it can read there.

  **Nothing in this namespace calls `h/raw` on a value that came from outside
  the repo.** `hiccup2.core` escapes content and attribute values by default,
  and that default is the project's answer to output encoding (ADR-0004 clause
  2) — catalog descriptions in particular arrive containing HTML."
  (:require [books.assets :as assets]
            [clojure.string :as str]
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
  {:now {:label "Available now" :class "text-emerald-700"}
   :next {:label "Coming next" :class "text-amber-700"}
   :later {:label "Coming later" :class "text-stone-400"}})

(def ^:private roadmap
  [{:title "Search"
    :blurb "Find a book in the Google Books catalog by title, by author, or by both."
    :status :now
    :href "/search"}
   {:title "Bookmarks"
    :blurb "Save the books you care about and find them again in one place."
    :status :next}
   {:title "Sign-in"
    :blurb "Your bookmarks, tied to you — an account so they follow you around."
    :status :later}])

(defn- roadmap-card [{:keys [title blurb status href]}]
  (let [{:keys [label] status-class :class} (statuses status)]
    [:div {:class "rounded-2xl border border-stone-200 bg-white p-6 shadow-sm"}
     [:h3 {:class "font-serif text-xl text-stone-900"}
      (if href
        [:a {:href href :class "underline decoration-stone-300 underline-offset-4 hover:text-amber-700"}
         title]
        title)]
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
    [:nav {:class "flex items-center gap-4"}
     [:a {:href "/search"
          :class "text-sm font-medium text-stone-600 underline decoration-stone-300 underline-offset-4 hover:text-stone-900"}
      "Search"]
     [:span {:class "hidden rounded-full border border-stone-300 px-3 py-1 text-xs font-medium text-stone-500 sm:inline"}
      "Sign-in coming soon"]]]])

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
      [:link {:rel "stylesheet" :href "/css/app.css"}]
      ;; Vendored, digest-pinned, served from our own origin — never a CDN
      ;; (ADR-0004, amended 2026-08-10). `defer` so it never blocks the render.
      [:script {:src assets/htmx-path :defer "defer"}]]
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

;; ---------------------------------------------------------------------------
;; The search page.
;;
;; ONE region, `#results`, in four states — a prompt, a list of Volumes, no
;; matches, or a failed search. Each carries a `data-state`, which is both what
;; the handler tests assert on and what tells a reader of this file that the
;; states are exhaustive.
;; ---------------------------------------------------------------------------

(def ^:private field-label "block text-sm font-medium text-stone-700")

(def ^:private field-input
  (str "mt-1 w-full rounded-lg border border-stone-300 bg-white px-3 py-2 text-stone-900 "
       "placeholder:text-stone-400 focus:border-amber-600 focus:outline-none focus:ring-2 focus:ring-amber-600/30"))

(defn- field [{:keys [name label placeholder value]}]
  [:div {:class "flex-1"}
   [:label {:for name :class field-label} label]
   [:input {:class field-input
            :type "search"
            :id name
            :name name
            :placeholder placeholder
            :value value}]])

(defn- search-form
  "The form, refilled with what was searched for so a full-page result (no
  JavaScript, or a shared URL) shows the query it answers."
  [{:keys [title author]}]
  [:form {:class "rounded-2xl border border-stone-200 bg-white p-6 shadow-sm"
          ;; Progressive enhancement: `method`/`action` make this an ordinary
          ;; GET form, and the hx-* attributes upgrade it to a fragment swap
          ;; when htmx is running. The endpoint is the same either way.
          :method "get"
          :action "/search"
          :hx-get "/search"
          :hx-target "#results"
          :hx-swap "outerHTML"
          :hx-push-url "true"
          :hx-indicator "#search-indicator"}
   [:div {:class "flex flex-col gap-4 sm:flex-row"}
    (field {:name "title" :label "Title" :placeholder "Brave New World" :value title})
    (field {:name "author" :label "Author" :placeholder "Aldous Huxley" :value author})]
   [:div {:class "mt-4 flex items-center gap-3"}
    [:button {:type "submit"
              :class (str "rounded-lg bg-amber-600 px-4 py-2 text-sm font-medium text-white "
                          "hover:bg-amber-700 focus:outline-none focus:ring-2 focus:ring-amber-600/40")}
     "Search"]
    ;; htmx toggles `htmx-request` on this element for the life of the
    ;; request; the two rules that make that visible are in styles/app.css.
    [:span {:id "search-indicator"
            :class "htmx-indicator flex items-center gap-2 text-sm text-stone-500"
            :role "status"}
     [:span {:class "h-2 w-2 animate-pulse rounded-full bg-amber-600" :aria-hidden "true"}]
     "Searching the catalog…"]]])

(def ^:private description-clamp
  "How much of a Volume's description the card shows: three rendered LINES.

  The catalog's blurbs run to paragraphs and a card shows a taste of one — but
  the card is short of lines, not of bytes, and how many characters fit on a
  line is a fact about the viewport, the font and the card's width that only
  the browser has. This used to be a substring cut at 240 characters with a
  backtrack to the nearest space and an appended ellipsis: it guessed at all
  three, and it could cut inside a grapheme cluster. `line-clamp-3` (Tailwind
  core since v3.3) does it where the facts are, ellipsis included."
  "line-clamp-3")

(defn- volume-card
  "One Volume. Every string here comes from the catalog and is therefore
  escaped by hiccup2 — no `h/raw`, ever."
  [{:keys [title authors published-date description thumbnail]}]
  [:li {:class "flex gap-4 rounded-2xl border border-stone-200 bg-white p-5 shadow-sm"}
   [:div {:class "hidden w-16 shrink-0 sm:block"}
    (if thumbnail
      [:img {:src thumbnail :alt "" :loading "lazy"
             :class "w-16 rounded border border-stone-200"}]
      [:div {:class "flex h-24 w-16 items-center justify-center rounded border border-dashed border-stone-300 text-xs text-stone-400"
             :aria-hidden "true"} "—"])]
   [:div {:class "min-w-0"}
    [:h3 {:class "font-serif text-lg leading-snug text-stone-900"} (or title "Untitled")]
    [:p {:class "mt-1 text-sm text-stone-600"}
     (if (seq authors) (str/join ", " authors) "Author unknown")
     (when published-date [:span {:class "text-stone-400"} " · " published-date])]
    (when (seq description)
      [:p {:class (classes "mt-2 text-sm leading-relaxed text-stone-600" description-clamp)}
       description])]])

(def ^:private notices
  "What the reader is told when the region is not a list of Volumes. The three
  failure reasons are named separately on purpose: 'come back in a minute',
  'the catalog is down' and 'this deployment has no key' are different facts,
  and only one of them is the reader's to act on."
  {:prompt
   {:heading "What are you looking for?"
    :detail "Search by title, by author, or by both."}
   :empty
   {:heading "No books matched that search."
    :detail "Try fewer words, or a different spelling of the author's name."}
   :quota
   {:heading "Too many searches just now."
    :detail "The catalog is rate-limiting us. Give it a minute and try again."}
   :unavailable
   {:heading "The catalog could not be reached."
    :detail "This is on our side, not yours. Try again shortly."}
   :not-configured
   {:heading "Search is not configured here."
    :detail "This deployment has no Google Books API key, so no search can run."}})

(defn- notice [kind]
  (let [{:keys [heading detail]} (notices kind)]
    [:div {:class "rounded-2xl border border-dashed border-stone-300 p-8 text-center"}
     [:p {:class "font-serif text-lg text-stone-900"} heading]
     [:p {:class "mt-2 text-sm text-stone-600"} detail]]))

(defn- results-region
  "The swappable region. `state` is a `books.catalog/search-volumes` result, or
  `{:outcome :prompt}` when there was nothing to search for."
  [{:keys [outcome reason volumes]}]
  (let [[data-state content]
        (case outcome
          :prompt ["prompt" (notice :prompt)]
          :error ["error" (notice reason)]
          (if (seq volumes)
            ["results" (into [:ul {:class "flex flex-col gap-4"}] (map volume-card volumes))]
            ["empty" (notice :empty)]))]
    [:div {:id "results"
           :data-state data-state
           :class "mt-8"
           ;; The region is replaced under the reader rather than navigated to,
           ;; so a screen reader is told it changed.
           :aria-live "polite"}
     content]))

(defn search-results
  "The results region ALONE — what htmx swaps in. Same function the page uses,
  so the two can never drift into rendering different things."
  [state]
  (str (h/html (results-region state))))

(defn search-page
  "The whole search page: the form (refilled from `query`) and the results
  region already in `state`, so a plain form GET or a shared URL answers with
  its results rather than an empty shell."
  [query state]
  (layout
   {:title (str "Search — " brand)}
   [:section {:class (classes container "pb-24 pt-12 sm:pt-16")}
    [:p {:class (classes "mb-4" eyebrow "text-amber-700")} "Search the catalog"]
    [:h1 {:class "max-w-2xl font-serif text-3xl leading-tight text-stone-900 sm:text-4xl"}
     "Find a book by title, by author, or by both."]
    [:div {:class "mt-8"} (search-form query)]
    (results-region state)]))
