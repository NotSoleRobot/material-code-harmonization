import com.sih.materialmaster.util.Iso7064Mod3736;

class ChecksumPresentationProbe {
    public static void main(String[] args) {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String base = "NUMM-40-14-07-000042";
        String body = Iso7064Mod3736.stripDelimiters(base);
        char check = Iso7064Mod3736.computeCheckChar(body);
        String full = body + check;
        int substitutions = 0, undetectedSubstitutions = 0, swaps = 0, undetectedSwaps = 0;
        for (int p = 0; p < full.length(); p++) {
            for (char c : alphabet.toCharArray()) {
                if (c == full.charAt(p)) continue;
                String changed = full.substring(0, p) + c + full.substring(p + 1);
                substitutions++;
                if (Iso7064Mod3736.computeCheckChar(changed.substring(0, changed.length()-1)) == changed.charAt(changed.length()-1)) undetectedSubstitutions++;
            }
        }
        for (int p = 0; p < full.length()-1; p++) {
            if (full.charAt(p) == full.charAt(p+1)) continue;
            StringBuilder changed = new StringBuilder(full);
            changed.setCharAt(p, full.charAt(p+1));
            changed.setCharAt(p+1, full.charAt(p));
            swaps++;
            if (Iso7064Mod3736.computeCheckChar(changed.substring(0, changed.length()-1)) == changed.charAt(changed.length()-1)) undetectedSwaps++;
        }
        System.out.printf("{\"code\":\"%s-%s\",\"valid\":%s,\"substitutions\":%d,\"undetectedSubstitutions\":%d,\"adjacentUnequalSwaps\":%d,\"undetectedSwaps\":%d}%n", base, check, Iso7064Mod3736.validate(base+"-"+check), substitutions, undetectedSubstitutions, swaps, undetectedSwaps);
    }
}
