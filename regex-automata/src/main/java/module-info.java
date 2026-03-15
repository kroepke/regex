module lol.ohai.regex.automata {
    requires lol.ohai.regex.syntax;
    exports lol.ohai.regex.automata.nfa.thompson;
    exports lol.ohai.regex.automata.nfa.thompson.pikevm;
    exports lol.ohai.regex.automata.nfa.thompson.backtrack;
    exports lol.ohai.regex.automata.meta;
    exports lol.ohai.regex.automata.util;
    exports lol.ohai.regex.automata.dfa;
    exports lol.ohai.regex.automata.dfa.lazy;
    exports lol.ohai.regex.automata.dfa.onepass;
}
