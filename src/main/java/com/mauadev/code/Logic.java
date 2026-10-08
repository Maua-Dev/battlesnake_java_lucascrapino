package com.mauadev.code;

// Bem-vindo ao
// __________         __    __  .__                               __
// \______   \_____ _/  |__/  |_|  |   ____   ______ ____ _____  |  | __ ____
//  |    |  _/\__  \   __\   __\  | _/ __ \ /  ___//    \__  \ |  |/ // __ \
//  |    |   \ / __ \|  |  |  | |  |_\  ___/ \___ \|   |  \/ __ \|    <\  ___/
//  |________/(______/__|  |__| |____/\_____>______>___|__(______/__|__\_____>
//


import com.mauadev.code.entities.Coordinate;
import com.mauadev.code.entities.GameState;
import com.mauadev.code.entities.Snake;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logica da cobra.
 * <p>
 * GET  /      -> {@link #info()}<br>
 * POST /start -> {@link #start(GameState)}<br>
 * POST /move  -> {@link #getMove(GameState)}<br>
 * POST /end   -> {@link #end(GameState)}
 */
public class Logic {

    private static final String[] MOVES = {"up", "down", "left", "right"};
    private static final int[] DX = {0, 0, -1, 1};
    private static final int[] DY = {1, -1, 0, 0};

    private static final double MORTE_CERTA = -1_000_000.0;
    private static final double CABECA_PERDEDORA = -5_000.0;
    private static final double ARMADILHA = -3_000.0;

    // Busca do mano a mano.
    private static final double VITORIA = 1_000_000.0;
    private static final double EMPATE = -500_000.0; // morrer junto ainda e nao ganhar

    // Tempo de pensamento. O `game.timeout` (500 ms no padrao) inclui a ida e
    // volta pela internet, entao usamos so uma fracao dele.
    private static final double FRACAO_DO_TIMEOUT = 0.40;
    private static final double TETO_MS = 160.0;

    /** Estourou o prazo da busca: fica com a profundidade anterior. */
    private static final class SemTempo extends RuntimeException {
        SemTempo() {
            super(null, null, false, false);
        }
    }

    /** Dano extra por turno em hazard no modo padrao (o Java nao recebe o ruleset). */
    private static final int HAZARD_DAMAGE = 14;

    /**
     * GET / - aparencia da cobra.
     * https://docs.battlesnake.com/guides/customizations
     */
    public static Map<String, String> info() {
        Map<String, String> info = new java.util.HashMap<>();
        info.put("apiversion", "1");
        info.put("author", "");          // TODO: coloque aqui o SEU usuario do Battlesnake
        info.put("color", "#8B0000");
        info.put("head", "tiger-king");
        info.put("tail", "hook");
        info.put("version", "2.0.0");
        return info;
    }

    /** POST /start - chamado uma vez, quando a partida comeca. */
    public static void start(GameState state) {
    }

    /** POST /end - chamado uma vez, quando a partida termina. */
    public static void end(GameState state) {
    }

    /**
     * POST /move - chamado a cada turno.
     *
     * @return "up", "down", "left" ou "right"
     */
    public static String getMove(GameState state) {
        long inicio = System.nanoTime();
        int timeoutMs = 500;
        if (state.getGame() != null && state.getGame().getTimeout() > 0) {
            timeoutMs = state.getGame().getTimeout();
        }
        double orcamentoMs = Math.min(TETO_MS, timeoutMs * FRACAO_DO_TIMEOUT);

        Tabuleiro tab = new Tabuleiro(state);
        tab.prazo = inicio + (long) (orcamentoMs * 1_000_000L);

        double[] notas = new double[4];
        int escolhido = 0;
        for (int m = 0; m < 4; m++) {
            notas[m] = avaliar(tab, m);
            if (notas[m] > notas[escolhido]) {
                escolhido = m;
            }
        }

        // Mano a mano: a busca decide e a heuristica desempata.
        if (tab.rivals.size() == 1 && tab.dentro(tab.myX[0], tab.myY[0])) {
            double[] busca = buscaManoAMano(tab, notas);
            if (busca != null) {
                escolhido = 0;
                for (int m = 1; m < 4; m++) {
                    if (busca[m] > busca[escolhido]
                            || (busca[m] == busca[escolhido] && notas[m] > notas[escolhido])) {
                        escolhido = m;
                    }
                }
            }
        }
        return MOVES[escolhido];
    }

    // ------------------------------------------------------------------
    // Representacao do tabuleiro
    // ------------------------------------------------------------------

    /** Adversario simplificado. */
    private static final class Rival {
        int[] xs;
        int[] ys;
        int len;
        int hx;
        int hy;
        int health;
    }

    /** Estado do jogo em arrays, para as buscas serem rapidas. */
    private static final class Tabuleiro {
        final int w;
        final int h;
        final int health;
        final int[] myX;
        final int[] myY;
        final int myLen;
        final boolean[] food;
        final boolean[] hazard;
        final List<Integer> foodList = new ArrayList<>();
        final List<Rival> rivals = new ArrayList<>();
        /** Em quantos turnos cada casa fica livre, considerando so os adversarios. */
        final int[] occRivais;
        /** Instante (System.nanoTime) em que a cobra precisa parar de pensar. */
        long prazo = Long.MAX_VALUE;

        Tabuleiro(GameState state) {
            w = state.getBoard() != null ? state.getBoard().getWidth() : 11;
            h = state.getBoard() != null ? state.getBoard().getHeight() : 11;
            Snake you = state.getYou();
            health = you.getHealth();

            List<Coordinate> body = you.getBody();
            if (body == null || body.isEmpty()) {
                body = List.of(you.getHead());
            }
            myLen = body.size();
            myX = new int[myLen];
            myY = new int[myLen];
            for (int i = 0; i < myLen; i++) {
                myX[i] = body.get(i).getX();
                myY[i] = body.get(i).getY();
            }

            food = new boolean[w * h];
            hazard = new boolean[w * h];
            if (state.getBoard() != null && state.getBoard().getFood() != null) {
                for (Coordinate c : state.getBoard().getFood()) {
                    if (dentro(c.getX(), c.getY())) {
                        food[idx(c.getX(), c.getY())] = true;
                        foodList.add(idx(c.getX(), c.getY()));
                    }
                }
            }
            if (state.getBoard() != null && state.getBoard().getHazards() != null) {
                for (Coordinate c : state.getBoard().getHazards()) {
                    if (dentro(c.getX(), c.getY())) {
                        hazard[idx(c.getX(), c.getY())] = true;
                    }
                }
            }

            occRivais = new int[w * h];
            if (state.getBoard() != null && state.getBoard().getSnakes() != null) {
                for (Snake s : state.getBoard().getSnakes()) {
                    if (s == null || s.getBody() == null || s.getBody().isEmpty()) {
                        continue;
                    }
                    if (s.getId() != null && s.getId().equals(you.getId())) {
                        continue;
                    }
                    Rival r = new Rival();
                    r.len = s.getBody().size();
                    r.xs = new int[r.len];
                    r.ys = new int[r.len];
                    for (int i = 0; i < r.len; i++) {
                        r.xs[i] = s.getBody().get(i).getX();
                        r.ys[i] = s.getBody().get(i).getY();
                    }
                    r.hx = r.xs[0];
                    r.hy = r.ys[0];
                    r.health = s.getHealth();
                    rivals.add(r);
                    marcarCorpo(occRivais, r.xs, r.ys, r.len, 0);
                }
            }
        }

        boolean dentro(int x, int y) {
            return x >= 0 && y >= 0 && x < w && y < h;
        }

        int idx(int x, int y) {
            return y * w + x;
        }

        /** Segmento i de uma cobra com n partes some depois de n - i movimentos. */
        void marcarCorpo(int[] occ, int[] xs, int[] ys, int n, int offset) {
            for (int i = 0; i < n; i++) {
                if (!dentro(xs[i], ys[i])) {
                    continue;
                }
                int k = idx(xs[i], ys[i]);
                int livreEm = offset + n - i;
                if (occ[k] < livreEm) {
                    occ[k] = livreEm;
                }
            }
        }

        double distCentro(int x, int y) {
            return Math.abs(x - (w - 1) / 2.0) + Math.abs(y - (h - 1) / 2.0);
        }
    }

    // ------------------------------------------------------------------
    // Buscas
    // ------------------------------------------------------------------

    /** BFS com nocao de tempo. Devolve as distancias (-1 = inalcancavel). */
    private static int[] floodFill(Tabuleiro t, int origem, int[] occ, int t0) {
        int[] dist = new int[t.w * t.h];
        Arrays.fill(dist, -1);
        ArrayDeque<Integer> fila = new ArrayDeque<>();
        dist[origem] = 0;
        fila.add(origem);
        while (!fila.isEmpty()) {
            int p = fila.poll();
            int px = p % t.w;
            int py = p / t.w;
            for (int d = 0; d < 4; d++) {
                int qx = px + DX[d];
                int qy = py + DY[d];
                if (!t.dentro(qx, qy)) {
                    continue;
                }
                int q = t.idx(qx, qy);
                if (dist[q] != -1 || occ[q] > t0 + dist[p] + 1) {
                    continue;
                }
                dist[q] = dist[p] + 1;
                fila.add(q);
            }
        }
        return dist;
    }

    /**
     * Quantos turnos a cobra aguenta viva a partir do corpo dado (cabeca ja na
     * casa nova, turno 1), simulando o proprio corpo andando passo a passo.
     * O flood fill sozinho e otimista: conta casas que so liberam no futuro,
     * mesmo quando nao da para esperar ate la.
     */
    private static int sobrevivencia(Tabuleiro t, int[] xs, int[] ys, int len, int alvo, int orcamento) {
        // Corpo como fila circular: cabeca em pos[head], cauda em pos[head + len - 1].
        int cap = len + alvo + 2;
        int[] pos = new int[cap];
        int[] noCorpo = new int[t.w * t.h];
        int head = alvo + 1;
        for (int i = 0; i < len; i++) {
            if (!t.dentro(xs[i], ys[i])) {
                pos[head + i] = -1;
                continue;
            }
            pos[head + i] = t.idx(xs[i], ys[i]);
            noCorpo[pos[head + i]]++;
        }
        int[] estado = {0, 0}; // {melhor, nos}
        dfsSobrevivencia(t, pos, noCorpo, head, len, 1, 0, alvo, orcamento, estado);
        return estado[0];
    }

    private static void dfsSobrevivencia(Tabuleiro t, int[] pos, int[] noCorpo, int head, int len,
                                         int turno, int prof, int alvo, int orcamento, int[] estado) {
        if (prof > estado[0]) {
            estado[0] = prof;
        }
        if (estado[0] >= alvo || estado[1] >= orcamento) {
            return;
        }
        estado[1]++;
        if ((estado[1] & 1023) == 0 && System.nanoTime() > t.prazo) {
            estado[1] = orcamento;
            return;
        }
        int cauda = pos[head + len - 1];
        int p = pos[head];
        int px = p % t.w;
        int py = p / t.w;
        if (cauda >= 0) {
            noCorpo[cauda]--; // a cauda sai do lugar neste passo
        }
        for (int d = 0; d < 4; d++) {
            int qx = px + DX[d];
            int qy = py + DY[d];
            if (!t.dentro(qx, qy)) {
                continue;
            }
            int q = t.idx(qx, qy);
            if (t.occRivais[q] > turno + 1 || noCorpo[q] > 0) {
                continue;
            }
            pos[head - 1] = q;
            noCorpo[q]++;
            dfsSobrevivencia(t, pos, noCorpo, head - 1, len, turno + 1, prof + 1, alvo, orcamento, estado);
            noCorpo[q]--;
            if (estado[0] >= alvo || estado[1] >= orcamento) {
                break;
            }
        }
        if (cauda >= 0) {
            noCorpo[cauda]++;
        }
    }

    /**
     * Voronoi: cada casa fica com quem chega primeiro. Eu parto da casa nova
     * (turno 1), os rivais das cabecas atuais (turno 0). Empate fica com o
     * maior; tamanhos iguais = ninguem.
     *
     * @return dono de cada casa: -1 ninguem, 0 eu, 1..n rival (indice + 1)
     */
    private static int[] voronoi(Tabuleiro t, int minhaCasa, int[] occ) {
        int n = t.w * t.h;
        int[] dono = new int[n];
        int[] tempo = new int[n];
        Arrays.fill(dono, -1);
        Arrays.fill(tempo, Integer.MAX_VALUE);

        int[] tamanho = new int[t.rivals.size() + 1];
        tamanho[0] = t.myLen;

        List<int[]> fronteira = new ArrayList<>();
        for (int r = 0; r < t.rivals.size(); r++) {
            Rival rv = t.rivals.get(r);
            tamanho[r + 1] = rv.len;
            if (t.dentro(rv.hx, rv.hy)) {
                int k = t.idx(rv.hx, rv.hy);
                dono[k] = r + 1;
                tempo[k] = 0;
                fronteira.add(new int[]{k, r + 1});
            }
        }

        int turno = 0;
        boolean primeiro = true;
        while (primeiro || !fronteira.isEmpty()) {
            List<int[]> prox = new ArrayList<>();
            for (int[] f : fronteira) {
                if (dono[f[0]] != f[1]) {
                    continue;
                }
                int px = f[0] % t.w;
                int py = f[0] / t.w;
                for (int d = 0; d < 4; d++) {
                    int qx = px + DX[d];
                    int qy = py + DY[d];
                    if (!t.dentro(qx, qy)) {
                        continue;
                    }
                    int q = t.idx(qx, qy);
                    if (occ[q] > turno + 1 || tempo[q] <= turno) {
                        continue;
                    }
                    prox.add(new int[]{q, f[1]});
                }
            }
            if (primeiro) {
                prox.add(new int[]{minhaCasa, 0});
                primeiro = false;
            }
            turno++;

            // Resolve chegadas simultaneas.
            Map<Integer, Set<Integer>> chegadas = new java.util.HashMap<>();
            for (int[] c : prox) {
                if (tempo[c[0]] < turno) {
                    continue;
                }
                chegadas.computeIfAbsent(c[0], k -> new HashSet<>()).add(c[1]);
            }
            fronteira = new ArrayList<>();
            for (Map.Entry<Integer, Set<Integer>> e : chegadas.entrySet()) {
                int q = e.getKey();
                tempo[q] = turno;
                int vencedor = -1;
                int maior = -1;
                boolean empate = false;
                for (int quem : e.getValue()) {
                    if (tamanho[quem] > maior) {
                        maior = tamanho[quem];
                        vencedor = quem;
                        empate = false;
                    } else if (tamanho[quem] == maior) {
                        empate = true;
                    }
                }
                dono[q] = empate ? -1 : vencedor;
                if (!empate) {
                    fronteira.add(new int[]{q, vencedor});
                }
            }
            if (turno > n) {
                break;
            }
        }
        return dono;
    }

    // ------------------------------------------------------------------
    // Avaliacao de cada jogada
    // ------------------------------------------------------------------

    private static double avaliar(Tabuleiro t, int m) {
        int nx = t.myX[0] + DX[m];
        int ny = t.myY[0] + DY[m];

        // 1. Morte certa: parede, corpo (a cauda que vai sair e liberada), fome.
        if (!t.dentro(nx, ny)) {
            return MORTE_CERTA;
        }
        int nova = t.idx(nx, ny);
        int[] occAtual = t.occRivais.clone();
        t.marcarCorpo(occAtual, t.myX, t.myY, t.myLen, 0);
        if (occAtual[nova] > 1) {
            return MORTE_CERTA;
        }

        boolean comeu = t.food[nova];
        int vida = comeu ? 100 : t.health - 1;
        if (t.hazard[nova] && !comeu) {
            vida -= HAZARD_DAMAGE;
        }
        if (vida <= 0) {
            return MORTE_CERTA;
        }

        // Corpo depois da jogada (se comer, a cauda fica no lugar).
        int novoLen = comeu ? t.myLen + 1 : t.myLen;
        int[] cx = new int[novoLen];
        int[] cy = new int[novoLen];
        cx[0] = nx;
        cy[0] = ny;
        for (int i = 1; i < novoLen; i++) {
            cx[i] = t.myX[i - 1];
            cy[i] = t.myY[i - 1];
        }

        double score = 0;
        int maiorRival = 0;
        for (Rival r : t.rivals) {
            maiorRival = Math.max(maiorRival, r.len);
        }

        // 2. Cabeca com cabeca: ataca menores, evita maiores ou iguais.
        for (Rival r : t.rivals) {
            int d = Math.abs(r.hx - nx) + Math.abs(r.hy - ny);
            if (d == 1) {
                if (r.len >= t.myLen) {
                    score += CABECA_PERDEDORA;
                } else {
                    score += 120;
                }
            }
        }

        // 3. Espaco: nada de beco sem saida.
        int[] occ = t.occRivais.clone();
        t.marcarCorpo(occ, cx, cy, novoLen, 1);
        occ[nova] = 0;
        int[] dist = floodFill(t, nova, occ, 1);
        int area = 0;
        for (int v : dist) {
            if (v >= 0) {
                area++;
            }
        }
        int alvo = Math.min(novoLen + 2, 40);
        int folego = sobrevivencia(t, cx, cy, novoLen, alvo, 40_000);
        if (folego < alvo) {
            // Beco: quanto mais turnos aguentar, menos ruim.
            score += ARMADILHA + folego * 80.0;
        } else if (area < novoLen) {
            score += -400 + area * 10.0;
        } else {
            score += Math.min(area, novoLen * 3);
        }

        // 4. Territorio e cerco.
        int[] dono = voronoi(t, nova, occ);
        int[] terr = new int[t.rivals.size() + 1];
        for (int v : dono) {
            if (v >= 0) {
                terr[v]++;
            }
        }
        // Fome dinamica: considera quao longe esta a comida alcancavel mais proxima.
        int maisPerto = Integer.MAX_VALUE;
        for (int f : t.foodList) {
            if (dist[f] >= 0) {
                maisPerto = Math.min(maisPerto, dist[f]);
            }
        }
        boolean fome = t.health < 30 || (maisPerto != Integer.MAX_VALUE && t.health <= maisPerto + 15);
        if (maisPerto != Integer.MAX_VALUE && t.health <= maisPerto + 6) {
            // Fome critica: chegar na comida vem antes de qualquer outra coisa.
            score -= 60.0 * maisPerto;
        }

        score += (fome ? 0.5 : 2.0) * terr[0];

        double raioCentro = Math.min(t.w, t.h) / 3.0;
        for (int r = 0; r < t.rivals.size(); r++) {
            Rival rv = t.rivals.get(r);
            int terrDele = terr[r + 1];
            boolean noCentro = t.distCentro(rv.hx, rv.hy) <= raioCentro + 1;
            int distAteEle = Math.abs(rv.hx - nx) + Math.abs(rv.hy - ny);

            // Encolher o espaco de quem invade o centro ou esta perto de mim.
            double peso = (noCentro || distAteEle <= 3) ? 1.5 : 0.5;
            score -= peso * terrDele;

            // Ficou sem espaco para o proprio corpo: cercado.
            if (terrDele < rv.len) {
                score += (noCentro || distAteEle <= 4) ? 400 : 150;
            }

            if (rv.len < t.myLen) {
                // Menor que eu e chegando no centro: vai pra cima dele.
                if (noCentro) {
                    score -= 5.0 * distAteEle;
                }
            } else if (distAteEle <= 2) {
                // Maior ou igual: mantem distancia da cabeca.
                score -= (3 - distAteEle) * 45.0;
            }
        }

        // 5. Centro do tabuleiro.
        double dc = t.distCentro(nx, ny);
        if (!fome) {
            if (dc <= raioCentro) {
                score += 30;
            } else {
                score -= 8.0 * (dc - raioCentro) + 10;
            }
        }

        // 6. Comida: prioridade para as frutas do centro.
        double vontade = t.myLen <= maiorRival + 1 ? 1.5 : 0.6;
        if (fome) {
            vontade = 2.5;
        }
        double melhor = 0;
        for (int f : t.foodList) {
            int d = dist[f];
            if (d < 0) {
                continue;
            }
            boolean minha = dono[f] == 0 || f == nova;
            double seguro = (minha || dono[f] == -1 || fome) ? 1.0 : 0.15;
            double local;
            if (fome) {
                local = 1.0;
            } else {
                double dcf = t.distCentro(f % t.w, f / t.w);
                local = dcf <= raioCentro ? 1.0 : (dcf <= raioCentro + 2 ? 0.5 : 0.15);
                if (t.myLen < maiorRival) {
                    // Menor que o maior rival: crescer vem antes de escolher fruta.
                    local = Math.max(local, 0.6);
                }
            }
            if (fome && t.health <= d + 2) {
                local = 2.0; // ou come agora, ou morre
            }
            double valor = 80.0 * local * seguro / (d + 1);
            if (fome) {
                // Com fome, a distancia pesa de forma linear: comida longe tambem conta.
                valor = Math.max(valor, (300.0 - 10.0 * d) * seguro);
            }
            melhor = Math.max(melhor, valor);
        }
        score += vontade * melhor;

        // 7. Hazard tira vida.
        if (t.hazard[nova] && !comeu) {
            score -= 40 + (100 - t.health);
        }

        return score;
    }

    // ------------------------------------------------------------------
    // Mano a mano: minimax com alpha-beta
    // ------------------------------------------------------------------

    /** Uma cobra dentro da simulacao: corpo (casas, cabeca primeiro) e vida. */
    private static final class Lado {
        final int[] corpo;
        final int vida;

        Lado(int[] corpo, int vida) {
            this.corpo = corpo;
            this.vida = vida;
        }
    }

    /** Resultado de um turno simulado. */
    private static final class Turno {
        Lado a;
        Lado b;
        boolean[] food;
        boolean vivoA;
        boolean vivoB;
    }

    private static int destino(Tabuleiro t, int c, int d) {
        int x = c % t.w + DX[d];
        int y = c / t.w + DY[d];
        return t.dentro(x, y) ? t.idx(x, y) : -1;
    }

    private static boolean contemDepoisDaCabeca(int[] corpo, int c) {
        for (int i = 1; i < corpo.length; i++) {
            if (corpo[i] == c) {
                return true;
            }
        }
        return false;
    }

    private static int[] mover(int[] corpo, int nova, boolean comeu) {
        int n = corpo.length;
        int[] r = new int[comeu ? n + 1 : n];
        r[0] = nova;
        System.arraycopy(corpo, 0, r, 1, n - 1);
        if (comeu) {
            r[n] = r[n - 1]; // cauda empilhada: cresce no proximo turno
        }
        return r;
    }

    /** Aplica as jogadas simultaneas seguindo a ordem oficial de resolucao. */
    private static Turno simular(Tabuleiro t, Lado a, Lado b, boolean[] food, int da, int db) {
        int ha = destino(t, a.corpo[0], da);
        int hb = destino(t, b.corpo[0], db);
        Turno r = new Turno();
        r.vivoA = ha >= 0;
        r.vivoB = hb >= 0;

        boolean comeuA = r.vivoA && food[ha];
        boolean comeuB = r.vivoB && food[hb];
        int vidaA = a.vida - 1;
        int vidaB = b.vida - 1;
        if (r.vivoA && t.hazard[ha] && !comeuA) {
            vidaA -= HAZARD_DAMAGE;
        }
        if (r.vivoB && t.hazard[hb] && !comeuB) {
            vidaB -= HAZARD_DAMAGE;
        }
        if (comeuA) {
            vidaA = 100;
        }
        if (comeuB) {
            vidaB = 100;
        }
        int[] ca = r.vivoA ? mover(a.corpo, ha, comeuA) : a.corpo;
        int[] cb = r.vivoB ? mover(b.corpo, hb, comeuB) : b.corpo;

        r.food = food;
        if (comeuA || comeuB) {
            r.food = food.clone();
            if (comeuA) {
                r.food[ha] = false;
            }
            if (comeuB) {
                r.food[hb] = false;
            }
        }

        boolean mortoA = !r.vivoA || vidaA <= 0 || contemDepoisDaCabeca(ca, ha)
                || (r.vivoB && contemDepoisDaCabeca(cb, ha));
        boolean mortoB = !r.vivoB || vidaB <= 0 || contemDepoisDaCabeca(cb, hb)
                || (r.vivoA && contemDepoisDaCabeca(ca, hb));
        if (r.vivoA && r.vivoB && ha == hb) {
            if (ca.length > cb.length) {
                mortoB = true;
            } else if (cb.length > ca.length) {
                mortoA = true;
            } else {
                mortoA = true;
                mortoB = true;
            }
        }
        r.vivoA = !mortoA;
        r.vivoB = !mortoB;
        r.a = new Lado(ca, vidaA);
        r.b = new Lado(cb, vidaB);
        return r;
    }

    /** Direcoes que nao batem na parede nem no pescoco. */
    private static int[] jogadasPlausiveis(Tabuleiro t, int[] corpo) {
        int pescoco = corpo.length > 1 ? corpo[1] : -1;
        int[] tmp = new int[4];
        int n = 0;
        for (int d = 0; d < 4; d++) {
            int q = destino(t, corpo[0], d);
            if (q >= 0 && q != pescoco) {
                tmp[n++] = d;
            }
        }
        if (n == 0) {
            return new int[]{0};
        }
        return Arrays.copyOf(tmp, n);
    }

    /** Avaliacao de uma posicao do mano a mano, do meu ponto de vista. */
    private static double avaliarFolha(Tabuleiro t, Lado a, Lado b, boolean[] food) {
        int n = t.w * t.h;
        int la = a.corpo.length;
        int lb = b.corpo.length;
        int[] occ = new int[n];
        for (int i = 0; i < la; i++) {
            occ[a.corpo[i]] = Math.max(occ[a.corpo[i]], la - i);
        }
        for (int i = 0; i < lb; i++) {
            occ[b.corpo[i]] = Math.max(occ[b.corpo[i]], lb - i);
        }

        // Voronoi das duas cabecas, partindo juntas.
        int[] dono = new int[n];
        int[] tempo = new int[n];
        Arrays.fill(dono, -1);
        Arrays.fill(tempo, Integer.MAX_VALUE);
        int[] fila = new int[n * 2];
        int ini = 0;
        int fim = 0;
        int ca = a.corpo[0];
        int cb = b.corpo[0];
        tempo[ca] = 0;
        dono[ca] = 0;
        fila[fim++] = ca;
        if (cb != ca) {
            tempo[cb] = 0;
            dono[cb] = 1;
            fila[fim++] = cb;
        }
        int meu = 0;
        int dele = 0;
        int comidaMinha = Integer.MAX_VALUE;
        int comidaQualquer = Integer.MAX_VALUE;
        while (ini < fim) {
            int p = fila[ini++];
            int quem = dono[p];
            if (quem < 0) {
                continue;
            }
            int px = p % t.w;
            int py = p / t.w;
            int prox = tempo[p] + 1;
            for (int d = 0; d < 4; d++) {
                int qx = px + DX[d];
                int qy = py + DY[d];
                if (!t.dentro(qx, qy)) {
                    continue;
                }
                int q = t.idx(qx, qy);
                if (occ[q] > prox) {
                    continue;
                }
                if (tempo[q] == Integer.MAX_VALUE) {
                    tempo[q] = prox;
                    dono[q] = quem;
                    fila[fim++] = q;
                } else if (tempo[q] == prox && dono[q] != quem && dono[q] != -1) {
                    // Chegada simultanea: fica com a maior; empate = ninguem.
                    if (la == lb) {
                        dono[q] = -1;
                    } else {
                        dono[q] = la > lb ? 0 : 1;
                    }
                }
            }
        }
        for (int c = 0; c < n; c++) {
            if (dono[c] == 0) {
                meu++;
                if (food[c] && tempo[c] < comidaMinha) {
                    comidaMinha = tempo[c];
                }
            } else if (dono[c] == 1) {
                dele++;
            }
            if (food[c] && tempo[c] < comidaQualquer) {
                comidaQualquer = tempo[c];
            }
        }

        double score = 4.0 * (meu - dele) + 25.0 * (la - lb);
        if (meu < la) {
            score -= 3000 - 40.0 * meu;
        }
        if (dele < lb) {
            score += 3000 - 40.0 * dele; // cercado
        }

        double pesoComida = (la <= lb + 1 || a.vida < 40) ? 60 : 15;
        if (comidaMinha != Integer.MAX_VALUE) {
            score += pesoComida / (comidaMinha + 1);
        }
        int alvoComida = comidaMinha != Integer.MAX_VALUE ? comidaMinha : comidaQualquer;
        if (alvoComida != Integer.MAX_VALUE) {
            int folga = a.vida - alvoComida;
            if (folga <= 0) {
                score -= 2000; // nao chega na comida a tempo
            } else if (folga < 20) {
                score -= (20 - folga) * 40.0; // fome apertando: puxa para a comida
            }
        } else if (a.vida < 30) {
            score -= (30 - a.vida) * 30.0;
        }

        // Estilo "centro": dono do meio e, se maior, em cima da cabeca dele.
        double dc = t.distCentro(ca % t.w, ca / t.w);
        score -= 3.0 * dc;
        if (la >= lb + 2) {
            score -= 8.0 * (Math.abs(ca % t.w - cb % t.w) + Math.abs(ca / t.w - cb / t.w));
        }
        return score;
    }

    private static double minimax(Tabuleiro t, Lado a, Lado b, boolean[] food, int prof,
                                  double alpha, double beta, int ply) {
        if (System.nanoTime() > t.prazo) {
            throw new SemTempo();
        }
        double melhor = Double.NEGATIVE_INFINITY;
        int[] minhas = jogadasPlausiveis(t, a.corpo);
        int[] dele = jogadasPlausiveis(t, b.corpo);
        for (int da : minhas) {
            double pior = Double.POSITIVE_INFINITY;
            for (int db : dele) {
                Turno r = simular(t, a, b, food, da, db);
                double v;
                if (!r.vivoA && !r.vivoB) {
                    v = EMPATE + ply;
                } else if (!r.vivoA) {
                    v = -VITORIA + ply;
                } else if (!r.vivoB) {
                    v = VITORIA - ply;
                } else if (prof <= 1) {
                    v = avaliarFolha(t, r.a, r.b, r.food);
                } else {
                    v = minimax(t, r.a, r.b, r.food, prof - 1, Math.max(alpha, melhor),
                            Math.min(beta, pior), ply + 1);
                }
                if (v < pior) {
                    pior = v;
                }
                if (pior <= Math.max(alpha, melhor)) {
                    break;
                }
            }
            if (pior > melhor) {
                melhor = pior;
            }
            if (melhor >= beta) {
                break;
            }
        }
        return melhor;
    }

    /** Aprofundamento iterativo ate acabar o tempo. Devolve a nota de cada direcao. */
    private static double[] buscaManoAMano(Tabuleiro t, double[] notasBase) {
        Rival rv = t.rivals.get(0);
        int[] meuCorpo = new int[t.myLen];
        for (int i = 0; i < t.myLen; i++) {
            if (!t.dentro(t.myX[i], t.myY[i])) {
                return null;
            }
            meuCorpo[i] = t.idx(t.myX[i], t.myY[i]);
        }
        int[] corpoDele = new int[rv.len];
        for (int i = 0; i < rv.len; i++) {
            if (!t.dentro(rv.xs[i], rv.ys[i])) {
                return null;
            }
            corpoDele[i] = t.idx(rv.xs[i], rv.ys[i]);
        }
        Lado a = new Lado(meuCorpo, t.health);
        Lado b = new Lado(corpoDele, rv.health);

        List<Integer> candidatas = new ArrayList<>();
        for (int d = 0; d < 4; d++) {
            if (notasBase[d] > MORTE_CERTA) {
                candidatas.add(d);
            }
        }
        if (candidatas.size() <= 1) {
            return null;
        }

        double[] resultado = null;
        for (int prof = 1; prof <= 20; prof++) {
            final double[] ref = resultado != null ? resultado : notasBase;
            candidatas.sort((x, y) -> Double.compare(ref[y], ref[x]));
            double[] atual = new double[4];
            Arrays.fill(atual, MORTE_CERTA);
            try {
                for (int da : candidatas) {
                    double pior = Double.POSITIVE_INFINITY;
                    for (int db : jogadasPlausiveis(t, b.corpo)) {
                        Turno r = simular(t, a, b, t.food, da, db);
                        double v;
                        if (!r.vivoA && !r.vivoB) {
                            v = EMPATE;
                        } else if (!r.vivoA) {
                            v = -VITORIA;
                        } else if (!r.vivoB) {
                            v = VITORIA;
                        } else if (prof <= 1) {
                            v = avaliarFolha(t, r.a, r.b, r.food);
                        } else {
                            v = minimax(t, r.a, r.b, r.food, prof - 1, Double.NEGATIVE_INFINITY, pior, 1);
                        }
                        pior = Math.min(pior, v);
                    }
                    atual[da] = pior;
                }
            } catch (SemTempo e) {
                break;
            }
            resultado = atual;
            double max = Double.NEGATIVE_INFINITY;
            for (double v : resultado) {
                max = Math.max(max, v);
            }
            if (max >= VITORIA - 100) {
                break; // vitoria garantida encontrada
            }
        }
        return resultado;
    }
}