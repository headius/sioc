(display "LOADING BASE.SCM\n")

#;
(import
  'java.util.List
  'java.lang.Class
  'java.dyn.MethodHandle)

(define equal? java.util.Objects#equals)

(define not (%bind-left java.util.Objects#equals #f))
(define boolean? (%bind-left java.lang.Class#isInstance java.lang.Boolean))

(define length java.util.List#size)
(define list? (%bind-left java.lang.Class#isInstance java.util.List))
(define list-ref java.util.List#get)
(define list-set! java.util.List#set)
;;(define list-tail (lambda (x n) (java.util.List#subList x n (length x))))
;;(define sublist java.util.List#subList)
(define vector->list java.util.Arrays#asList)
(define list->vector java.util.List#toArray:1))
(define car (%bind-right list-ref 0))
(define cdr (%bind-right list-tail 1))
(define append! java.util.List#addAll)

;;native symbol? symbol->string string->symbol
;;native list-tail
;;native %bind-left, %bind-right
;;native %method-type should be java.dyn.MethodType#methodType

(define char? (%bind-left java.lang.Class#isInstance java.lang.Character))
;;(define char->integer (java.dyn.MethodHandles#identity (%method-type java.lang.Integer#TYPE java.lang.Character#TYPE)))
;;(define integer->char (java.dyn.MethodHandles#identity (%method-type java.lang.Character#TYPE java.lang.Integer#TYPE)))

(define string? (%bind-left java.lang.Class#isInstance java.lang.String))
(define string-ref java.lang.String#charAt)
(define string-length java.lang.String#length)
(define substring java.lang.String#substring:3)
(define string-append java.lang.String#concat)

(define vector? (%compose java.lang.Class#isArray java.lang.Object#getClass))
(define vector-ref java.lang.reflect.Array#get)
(define vector-length java.lang.reflect.Array#getLength)
(define vector-set! java.lang.reflect.Array#set)
(define make-vector (%bind-left java.lang.reflect.Array#newInstance java.lang.Object))
