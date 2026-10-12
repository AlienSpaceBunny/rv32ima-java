## Per image: HEAD / v0.1.8 median time ratio (>1 = HEAD slower)

| image | cells | min | median | max | slowest cell | ranges overlap |
| --- | --- | --- | --- | --- | --- | --- |
| jvm | 144 | 0.956 | 1.072 | 1.229 | directbuffer/copy6m-ap | 120/144 |
| jvm-dc4096 | 144 | 0.874 | 1.004 | 1.106 | mmio/reg-ap-step1 | 127/144 |
| mono | 64 | 0.958 | 1.018 | 1.150 | bytearray/reg-ap-step1 | 34/64 |
| mono-dc4096 | 64 | 0.919 | 1.010 | 1.155 | bytearray/reg-ap-step1 | 42/64 |
| poly | 144 | 0.930 | 1.002 | 1.123 | heapbuffer/host-read6m | 85/144 |
| poly-dc4096 | 144 | 0.917 | 0.974 | 1.175 | wrapper/reg-ap-step1 | 36/144 |
| poly-o3 | 32 | 0.981 | 1.007 | 1.116 | bytearray/reg-ap-step1 | 24/32 |
| poly-o3-dc4096 | 32 | 0.940 | 0.970 | 1.100 | bytearray/reg-ap-step1 | 9/32 |
| poly-v2 | 32 | 0.975 | 1.002 | 1.130 | ffm/reg-ap-step1 | 24/32 |
| poly-v3 | 32 | 0.916 | 0.992 | 1.122 | bytearray/reg-ap-step1 | 12/32 |

## Per workload (all images and buses)

| workload | cells | min | median | max |
| --- | --- | --- | --- | --- |
| collide-ap | 52 | 0.874 | 1.010 | 1.186 |
| copy4k-ap | 52 | 0.936 | 1.012 | 1.225 |
| copy6m-ap | 52 | 0.926 | 1.006 | 1.229 |
| coremark-c | 52 | 0.926 | 1.004 | 1.142 |
| coremark-noc | 52 | 0.935 | 0.994 | 1.103 |
| fp-ap | 52 | 0.928 | 1.002 | 1.160 |
| fpkernels-c | 52 | 0.945 | 1.000 | 1.134 |
| fpkernels-noc | 52 | 0.952 | 0.996 | 1.081 |
| host-copy4k | 52 | 0.930 | 0.999 | 1.062 |
| host-read6m | 52 | 0.913 | 1.007 | 1.137 |
| mix-ap | 52 | 0.917 | 1.002 | 1.195 |
| reg-ap | 52 | 0.916 | 1.009 | 1.219 |
| reg-ap-step1 | 52 | 0.900 | 1.100 | 1.218 |
| reg-ima | 52 | 0.936 | 0.998 | 1.085 |
| regc-ap | 52 | 0.926 | 1.007 | 1.182 |
| spread-ap | 52 | 0.943 | 1.008 | 1.135 |

## Cells at or above 1.05 with non-overlapping ranges

| ratio | image | bus | workload |
| --- | --- | --- | --- |
| 1.205 | jvm | heapsegment | reg-ap-step1 |
| 1.175 | poly-dc4096 | wrapper | reg-ap-step1 |
| 1.175 | poly-dc4096 | heapsegment | reg-ap-step1 |
| 1.171 | poly-dc4096 | router | reg-ap-step1 |
| 1.171 | poly-dc4096 | bytearray | reg-ap-step1 |
| 1.170 | poly-dc4096 | ffm | reg-ap-step1 |
| 1.170 | poly-dc4096 | mmio | reg-ap-step1 |
| 1.169 | poly-dc4096 | directbuffer | reg-ap-step1 |
| 1.169 | poly-dc4096 | varhandle | reg-ap-step1 |
| 1.168 | jvm | varhandle | mix-ap |
| 1.163 | poly-dc4096 | heapbuffer | reg-ap-step1 |
| 1.155 | mono-dc4096 | bytearray | reg-ap-step1 |
| 1.153 | mono-dc4096 | ffm | reg-ap-step1 |
| 1.150 | mono | bytearray | reg-ap-step1 |
| 1.148 | jvm | varhandle | copy4k-ap |
| 1.147 | jvm | varhandle | copy6m-ap |
| 1.144 | jvm | mmio | mix-ap |
| 1.142 | mono-dc4096 | router | reg-ap-step1 |
| 1.137 | poly-dc4096 | heapbuffer | host-read6m |
| 1.135 | jvm | heapsegment | spread-ap |
| 1.134 | jvm | directbuffer | fpkernels-c |
| 1.130 | poly-v2 | ffm | reg-ap-step1 |
| 1.128 | jvm | heapsegment | fp-ap |
| 1.123 | poly | heapbuffer | host-read6m |
| 1.122 | poly-v3 | bytearray | reg-ap-step1 |
| 1.121 | jvm | heapbuffer | reg-ap-step1 |
| 1.119 | jvm | mmio | reg-ap-step1 |
| 1.116 | poly-o3 | bytearray | reg-ap-step1 |
| 1.113 | jvm | mmio | copy4k-ap |
| 1.111 | poly | mmio | mix-ap |
| 1.109 | jvm | ffm | fpkernels-c |
| 1.103 | jvm | mmio | coremark-noc |
| 1.101 | jvm | router | mix-ap |
| 1.100 | poly-o3-dc4096 | bytearray | reg-ap-step1 |
| 1.099 | poly-o3-dc4096 | ffm | reg-ap-step1 |
| 1.096 | jvm | mmio | copy6m-ap |
| 1.092 | poly | bytearray | reg-ap-step1 |
| 1.092 | jvm | heapsegment | fpkernels-c |
| 1.085 | jvm | wrapper | fpkernels-c |
| 1.085 | mono | directbuffer | reg-ima |
| 1.084 | poly | directbuffer | host-read6m |
| 1.081 | jvm | heapsegment | fpkernels-noc |
| 1.079 | jvm | wrapper | coremark-noc |
| 1.078 | jvm-dc4096 | heapbuffer | fpkernels-c |
| 1.076 | mono | router | reg-ap-step1 |
| 1.075 | jvm-dc4096 | heapbuffer | fpkernels-noc |
| 1.073 | poly | wrapper | reg-ap-step1 |
| 1.072 | poly | mmio | reg-ap-step1 |
| 1.069 | poly-dc4096 | directbuffer | host-read6m |
| 1.068 | poly-o3 | ffm | reg-ap-step1 |
| 1.067 | mono | router | mix-ap |
| 1.067 | poly | varhandle | reg-ap-step1 |
| 1.064 | jvm | heapsegment | mix-ap |
| 1.062 | mono | directbuffer | regc-ap |
| 1.061 | jvm | directbuffer | fpkernels-noc |
| 1.060 | poly | mmio | copy6m-ap |
| 1.058 | jvm | wrapper | fpkernels-noc |
| 1.058 | mono | directbuffer | fp-ap |
| 1.056 | mono-dc4096 | directbuffer | reg-ap-step1 |
| 1.054 | mono | router | fp-ap |
| 1.053 | poly | router | reg-ap-step1 |
| 1.053 | poly | varhandle | host-read6m |
| 1.051 | poly | mmio | copy4k-ap |
| 1.051 | mono-dc4096 | ffm | fp-ap |
| 1.051 | jvm-dc4096 | directbuffer | spread-ap |
