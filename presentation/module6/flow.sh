#!/usr/bin/env bash
# The demo flow, as HTTP calls - the fallback for beat 1 of demo-script.md when the shop page will not
# cooperate, and a quick smoke test of a running instance.
#
# Usage:  presentation/module6/recordings/flow.sh        (with the app running on :8080)
set -e
B=http://localhost:8080
PID=prod-$RANDOM; BID=basket-$RANDOM; OID=order-$RANDOM
echo "--- add product"
curl -s -o /dev/null -w "%{http_code}\n" -X POST $B/api/products -H 'Content-Type: application/json' \
  -d "{\"id\":\"$PID\",\"name\":\"Espresso machine\",\"price\":2499.00}"
echo "--- change price (200), same price again (204)"
curl -s -o /dev/null -w "%{http_code} " -X PUT $B/api/products/$PID/price -H 'Content-Type: application/json' -d '{"price":1999.50}'
curl -s -o /dev/null -w "%{http_code}\n" -X PUT $B/api/products/$PID/price -H 'Content-Type: application/json' -d '{"price":1999.50}'
sleep 1
echo "--- products for sale view"
curl -s $B/api/products-for-sale
echo; echo "--- add 2 items to basket"
curl -s -o /dev/null -w "%{http_code} " -X POST $B/api/shopping-baskets/$BID/items -H 'Content-Type: application/json' -d "{\"product\":\"$PID\",\"price\":1999.50}"
curl -s -o /dev/null -w "%{http_code}\n" -X POST $B/api/shopping-baskets/$BID/items -H 'Content-Type: application/json' -d "{\"product\":\"$PID\",\"price\":1999.50}"
sleep 1
echo "--- basket view"; curl -s $B/api/shopping-baskets/$BID
echo; echo "--- remove one"; curl -s -o /dev/null -w "%{http_code}\n" -X DELETE $B/api/shopping-baskets/$BID/items/$PID
sleep 1; curl -s $B/api/shopping-baskets/$BID
echo; echo "--- checkout"
curl -s -X POST $B/api/shopping-baskets/$BID/checkout -H 'Content-Type: application/json' -d "{\"orderId\":\"$OID\"}"
echo; echo "--- shipping + payment details"
curl -s -o /dev/null -w "%{http_code} " -X PUT $B/api/orders/$OID/shipping-details -H 'Content-Type: application/json' \
  -d '{"shippingAddress":{"street":"Vestergade 1","postalCode":"8000","city":"Aarhus","countryCode":"DK"},"shippingMethod":"STANDARD"}'
curl -s -o /dev/null -w "%{http_code}\n" -X PUT $B/api/orders/$OID/payment-details -H 'Content-Type: application/json' \
  -d '{"invoiceAddress":{"street":"Vestergade 1","postalCode":"8000","city":"Aarhus","countryCode":"DK"},"paymentMethod":"CREDIT_CARD"}'
sleep 1
echo "--- place order"; curl -s -o /dev/null -w "%{http_code}\n" -X POST $B/api/orders/$OID/place
sleep 3
echo "--- order summary (payment hold should be HELD)"; curl -s $B/api/orders/$OID/summary
echo; echo "--- packaging list"; curl -s $B/api/shipping/orders-ready-for-packaging
echo; echo "--- package + ship"
curl -s -o /dev/null -w "%{http_code} " -X POST $B/api/shipping/orders/$OID/package
sleep 1
curl -s -o /dev/null -w "%{http_code}\n" -X POST $B/api/shipping/orders/$OID/ship -H 'Content-Type: application/json' -d '{"trackingNumber":"TRACK-12345"}'
sleep 3
echo "--- final summary"; curl -s $B/api/orders/$OID/summary
echo; echo "ORDER=$OID"
