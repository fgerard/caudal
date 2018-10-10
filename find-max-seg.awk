# este awk valida cosas sacadas del log
{
  hms = substr($1,1,8);
  contador[hms]+=1;
}

END {
  for (hmss in contador) {
    print(contador[hmss] "\t\t" hmss);
  }
}
