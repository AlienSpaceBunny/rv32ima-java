## Per image: HEAD / v0.1.8 median time ratio (>1 = HEAD slower)

| image | cells | min | median | max | slowest cell | ranges overlap |
| --- | --- | --- | --- | --- | --- | --- |
| jvm | 144 | 0.948 | 1.046 | 1.238 | varhandle/regc-ap | 111/144 |

## Per workload (all images and buses)

| workload | cells | min | median | max |
| --- | --- | --- | --- | --- |
| collide-ap | 9 | 1.005 | 1.063 | 1.215 |
| copy4k-ap | 9 | 1.006 | 1.068 | 1.214 |
| copy6m-ap | 9 | 1.002 | 1.064 | 1.217 |
| coremark-c | 9 | 1.010 | 1.077 | 1.205 |
| coremark-noc | 9 | 0.990 | 1.040 | 1.136 |
| fp-ap | 9 | 0.952 | 1.049 | 1.105 |
| fpkernels-c | 9 | 0.979 | 1.027 | 1.096 |
| fpkernels-noc | 9 | 0.958 | 0.998 | 1.086 |
| host-copy4k | 9 | 0.984 | 0.998 | 1.032 |
| host-read6m | 9 | 0.959 | 1.001 | 1.019 |
| mix-ap | 9 | 0.996 | 1.114 | 1.199 |
| reg-ap | 9 | 0.990 | 1.065 | 1.230 |
| reg-ap-step1 | 9 | 0.995 | 1.086 | 1.159 |
| reg-ima | 9 | 0.948 | 1.015 | 1.165 |
| regc-ap | 9 | 0.974 | 1.047 | 1.238 |
| spread-ap | 9 | 0.965 | 1.034 | 1.147 |

## Cells at or above 1.05 with non-overlapping ranges

| ratio | image | bus | workload |
| --- | --- | --- | --- |
| 1.238 | jvm | varhandle | regc-ap |
| 1.230 | jvm | varhandle | reg-ap |
| 1.217 | jvm | varhandle | copy6m-ap |
| 1.215 | jvm | varhandle | collide-ap |
| 1.214 | jvm | varhandle | copy4k-ap |
| 1.205 | jvm | varhandle | coremark-c |
| 1.199 | jvm | varhandle | mix-ap |
| 1.185 | jvm | mmio | reg-ap |
| 1.159 | jvm | varhandle | reg-ap-step1 |
| 1.147 | jvm | mmio | spread-ap |
| 1.141 | jvm | router | mix-ap |
| 1.132 | jvm | ffm | reg-ap-step1 |
| 1.128 | jvm | bytearray | mix-ap |
| 1.126 | jvm | mmio | mix-ap |
| 1.117 | jvm | bytearray | copy4k-ap |
| 1.117 | jvm | bytearray | copy6m-ap |
| 1.114 | jvm | heapbuffer | mix-ap |
| 1.109 | jvm | wrapper | mix-ap |
| 1.105 | jvm | bytearray | fp-ap |
| 1.105 | jvm | wrapper | fp-ap |
| 1.102 | jvm | mmio | copy4k-ap |
| 1.101 | jvm | bytearray | spread-ap |
| 1.100 | jvm | mmio | copy6m-ap |
| 1.096 | jvm | wrapper | fpkernels-c |
| 1.094 | jvm | mmio | fp-ap |
| 1.092 | jvm | mmio | coremark-noc |
| 1.086 | jvm | router | reg-ap-step1 |
| 1.086 | jvm | wrapper | fpkernels-noc |
| 1.076 | jvm | directbuffer | reg-ap-step1 |
| 1.070 | jvm | varhandle | spread-ap |
| 1.055 | jvm | directbuffer | mix-ap |
