(ns books.views
  "Server-rendered pages (Hiccup). `layout` is the shared page frame —
  header, content area, footer — every page reuses; styling comes from the
  Tailwind-built stylesheet at /css/app.css (see styles/app.css)."
  (:require [hiccup2.core :as h]))

(def ^:private brand "Google Books")

(defn- header []
  [:header {:class "border-b border-stone-200 bg-white/80 backdrop-blur"}
   [:div {:class "mx-auto flex max-w-5xl items-center justify-between px-6 py-4"}
    [:a {:href "/" :class "flex items-center gap-2 text-lg font-semibold tracking-tight text-stone-900"}
     [:span {:class "inline-flex h-8 w-8 items-center justify-center rounded-lg bg-amber-600 font-serif text-base text-white"
             :aria-hidden "true"} "B"]
     brand]
    [:span {:class "rounded-full border border-stone-300 px-3 py-1 text-xs font-medium text-stone-500"}
     "Sign-in coming soon"]]])

(defn- footer []
  [:footer {:class "border-t border-stone-200"}
   [:div {:class "mx-auto flex max-w-5xl flex-col gap-2 px-6 py-8 text-sm text-stone-500 sm:flex-row sm:items-center sm:justify-between"}
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
   {:title "Google Books — search the catalog, bookmark the keepers"}
   [:section {:class "mx-auto max-w-5xl px-6 pb-16 pt-16 sm:pt-24"}
    [:p {:class "mb-4 text-sm font-medium uppercase tracking-widest text-amber-700"}
     "A reading companion, in the making"]
    [:h1 {:class "max-w-2xl font-serif text-4xl leading-tight text-stone-900 sm:text-5xl"}
     "Search the Google Books catalog. Keep the books that matter."]
    [:p {:class "mt-6 max-w-xl text-lg leading-relaxed text-stone-600"}
     "This app will let you explore millions of titles through the Google Books"
     " API and bookmark the ones you want to come back to. It is being built in"
     " the open, one small slice at a time — today the service is up and"
     " serving; the features below are on their way."]]
   [:section {:class "mx-auto max-w-5xl px-6 pb-24"}
    [:h2 {:class "mb-6 text-sm font-medium uppercase tracking-widest text-stone-400"}
     "What's coming"]
    [:div {:class "grid gap-6 sm:grid-cols-3"}
     [:div {:class "rounded-2xl border border-stone-200 bg-white p-6 shadow-sm"}
      [:h3 {:class "font-serif text-xl text-stone-900"} "Search"]
      [:p {:class "mt-2 text-sm leading-relaxed text-stone-600"}
       "Find any book in the Google Books catalog by title, author, or keyword."]
      [:p {:class "mt-4 text-xs font-medium uppercase tracking-wide text-amber-700"} "Coming next"]]
     [:div {:class "rounded-2xl border border-stone-200 bg-white p-6 shadow-sm"}
      [:h3 {:class "font-serif text-xl text-stone-900"} "Bookmarks"]
      [:p {:class "mt-2 text-sm leading-relaxed text-stone-600"}
       "Save the books you care about and find them again in one place."]
      [:p {:class "mt-4 text-xs font-medium uppercase tracking-wide text-amber-700"} "Coming next"]]
     [:div {:class "rounded-2xl border border-stone-200 bg-white p-6 shadow-sm"}
      [:h3 {:class "font-serif text-xl text-stone-900"} "Sign-in"]
      [:p {:class "mt-2 text-sm leading-relaxed text-stone-600"}
       "Your bookmarks, tied to you — an account so they follow you around."]
      [:p {:class "mt-4 text-xs font-medium uppercase tracking-wide text-stone-400"} "Coming later"]]]]))
