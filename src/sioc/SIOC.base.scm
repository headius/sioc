(import
  'java.util.List
  'java.lang.Class
  'java.dyn.MethodHandle)

(define list? (MethodHandle#bindTo Class#isInstance List))
;;(set! list? (java.dyn.MethodHandle#bindTo java.lang.Class#isInstance java.util.List))
(define list-ref List#get)
(define list-set! List#set)
(define sublist List#subList)
(define array-list java.util.Arrays#asList)

(define equal? java.util.Objects#equals)

(define append! List#addAll)
;; symbol? symbol->string list-tail