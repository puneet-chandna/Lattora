#!/usr/bin/env python3
"""Independent stdlib equation/placement oracle; fixed draw tape, no Java invocation.

Run without arguments for reference self-checks; pass JUnit JSON to compare every
objective call and final population best. Java fitness is never reference truth.
"""
import itertools
import json
import math
import sys

GAUSSIANS = (0, -1.25, .5, 0, 2, -.75, 1.5, -2, .25)


class Tape:
    def __init__(self, offset):
        self.u_index = self.g_index = 0
        self.offset = offset

    def u(self):
        result = ((37 * self.u_index + 11 + self.offset) % 101) / 101
        self.u_index += 1
        return result

    def integer(self, limit):
        return int(limit * self.u())

    def gaussian(self):
        result = GAUSSIANS[self.g_index % len(GAUSSIANS)]
        self.g_index += 1
        return result


# Fixture hosts: capacity in VM units, idle power, full power. Each VM is
# 1 PE/3000MIPS/10 RAM/10 BW/10 disk, so all cumulative constraints agree here.
HOSTS = ((2, 175, 250), (4, 175, 250), (4, 210, 300))


def feasible(mapping):
    return all(mapping.count(h) <= host[0] for h, host in enumerate(HOSTS))


def power(mapping):
    return sum(idle + (full - idle) * mapping.count(h) / pes
               for h, (pes, idle, full) in enumerate(HOSTS) if h in mapping)


def repair(genes):
    if not all(map(math.isfinite, genes)):
        return None
    mapping = []
    for gene in genes:
        decoded = min(2, math.floor(min(1, max(0, gene)) * 3))
        choices = [h for h in range(3) if feasible(mapping + [h])]
        if not choices:
            return None
        selected = decoded if decoded in choices else min(
            choices, key=lambda h: (power(mapping + [h]) - power(mapping), h))
        mapping.append(selected)
    return mapping


def defense_equation(predator, incumbent, levy, f, c, d, g, noise=None):
    distances = [abs(a-b) for a, b in zip(predator, incumbent)]
    divisor = c-d*math.cos(2*math.pi*g)
    factor = f/divisor if divisor != 0 else math.copysign(math.inf, divisor)
    reciprocal_divisors = distances if noise is None else [2*a+b for a, b in zip(distances, noise)]
    return [jump*p + factor*(1/(distance or sys.float_info.min))
            for jump, p, distance in zip(levy, predator, reciprocal_divisors)]


def reference(algorithm, n, iterations, offset):
    tape, rows, coverage = Tape(offset), [], set()
    size, budget = 4, n + 3 * n * iterations
    vector = lambda: [tape.u() for _ in range(size)]
    key = lambda a: (a['fitness'], a['x'], a['id'])

    def evaluate(values, t, phase, member, dominant=-1, incumbent=None, eligible=True):
        finite = all(map(math.isfinite, values))
        bounded = [min(1, max(0, g)) for g in values] if finite else values
        mapping = repair(bounded)
        fitness = power(mapping) if mapping is not None else math.inf
        candidate = dict(x=bounded, mapping=mapping, fitness=fitness, id=len(rows))
        accepted = eligible and (incumbent is None or fitness < incumbent['fitness'])
        coverage.add((phase, accepted))
        rows.append(dict(evaluation=len(rows)+1, iteration=t, phase=phase, member=member,
                         dominant=dominant, genes=bounded if finite else None, plan=mapping,
                         watts=fitness if math.isfinite(fitness) else None, accepted=accepted))
        return candidate

    def accept(candidate, incumbent):
        return candidate if candidate['fitness'] < incumbent['fitness'] else incumbent

    population = [evaluate(vector(), 0, 'INITIAL', i) for i in range(n)]
    if algorithm == 'GA':
        generation = 0
        while len(rows) < budget:
            generation += 1
            children = [min(population, key=key)]
            while len(children) < n and len(rows) < budget:
                parents = [min([population[tape.integer(n)] for _ in range(3)], key=key)['x'][:]
                           for _ in range(2)]
                if tape.u() < .8:
                    cut = 1 + tape.integer(size-1)
                    parents = [parents[0][:cut]+parents[1][cut:], parents[1][:cut]+parents[0][cut:]]
                for child in parents:
                    if len(children) == n or len(rows) == budget:
                        break
                    child = [tape.u() if tape.u() < 1/size else g for g in child]
                    children.append(evaluate(child, generation, 'CHILD', len(children)))
            population = children
    else:
        beta = 1.5
        sigma = (math.gamma(1+beta)*math.sin(math.pi*beta/2)
                 /(math.gamma((1+beta)/2)*beta*2**((beta-1)/2)))**(1/beta)
        for t in range(1, iterations+1):
            leader = min(population, key=key)
            dominant, leader_id = leader['x'], leader['id']
            for i in range(n//2):
                incumbent = population[i]
                x = incumbent['x']
                i1, i2, rho1, rho2 = 1+tape.integer(2), 1+tape.integer(2), tape.integer(2), tape.integer(2)
                count = 1+tape.integer(n)
                group = list(range(n))
                for k in range(count):
                    position = k+tape.integer(n-k)
                    group[k], group[position] = group[position], group[k]
                mean = [sum(population[k]['x'][j] for k in group[:count])/count for j in range(size)]
                alternatives = [[i2*u+1-rho1 for u in vector()], [2*u-1 for u in vector()],
                                vector(), [i1*u+1-rho2 for u in vector()], [tape.u()]*size]
                choice_a, choice_b = tape.integer(5), tape.integer(5)
                coverage.update((('h', choice_a), ('h', choice_b)))
                a, b = alternatives[choice_a], alternatives[choice_b]
                y = tape.u()
                male = [v+y*(d-i1*v) for v, d in zip(x, dominant)]
                if math.exp(-t/iterations) > .6:
                    coverage.add('warm')
                    female = [v+h*(d-i2*m) for v, h, d, m in zip(x, a, dominant, mean)]
                elif tape.u() > .5:
                    coverage.add('cool-near')
                    female = [v+h*(m-d) for v, h, m, d in zip(x, b, mean, dominant)]
                else:
                    coverage.add('cool-restart')
                    female = [tape.u()]*size
                incumbent = accept(evaluate(male, t, 'RIVER_MALE', i, leader_id, incumbent), incumbent)
                population[i] = accept(evaluate(female, t, 'RIVER_FEMALE', i, leader_id, incumbent), incumbent)
            for i in range(n//2, n):
                incumbent = population[i]
                predator = evaluate(vector(), t, 'PREDATOR', i, leader_id, eligible=False)
                f, c, d, g = 2+2*tape.u(), 1+.5*tape.u(), 2+tape.u(), 2*tape.u()-1
                levi = []
                for _ in range(size):
                    numerator, denominator = tape.gaussian(), tape.gaussian()
                    while denominator == 0:
                        coverage.add('gaussian-redraw')
                        denominator = tape.gaussian()
                    levi.append(.05*sigma*numerator/abs(denominator)**(1/beta))
                coverage.add(('predator-better', predator['fitness'] < incumbent['fitness']))
                noise = None if predator['fitness'] < incumbent['fitness'] else vector()
                defense = defense_equation(predator['x'], incumbent['x'], levi, f, c, d, g, noise)
                population[i] = accept(evaluate(defense, t, 'DEFENSE', i, leader_id, incumbent), incumbent)
            for i, incumbent in enumerate(population):
                alternatives = [[2*u-1 for u in vector()], [tape.gaussian()]*size, [tape.u()]*size]
                choice = tape.integer(3)
                coverage.add(('escape', choice))
                direction = alternatives[choice]
                distance = tape.u()
                escape = [v+distance*s/t for v, s in zip(incumbent['x'], direction)]
                population[i] = accept(evaluate(escape, t, 'ESCAPE', i, leader_id, incumbent), incumbent)
    best = min(population, key=key)
    assert len(rows) == budget
    return rows, best, (tape.u_index, tape.g_index), coverage


def compare(actual, expected, location='root'):
    if isinstance(expected, dict):
        assert actual.keys() == expected.keys(), (location, actual.keys(), expected.keys())
        for k in expected:
            compare(actual[k], expected[k], location+'.'+k)
    elif isinstance(expected, list):
        assert len(actual) == len(expected), location
        for i, (a, b) in enumerate(zip(actual, expected)):
            compare(a, b, location+f'[{i}]')
    elif isinstance(expected, float):
        assert math.isclose(actual, expected, rel_tol=2e-13, abs_tol=2e-13), (location, actual, expected)
    else:
        assert actual == expected, (location, actual, expected)


def self_check():
    mappings = [list(m) for m in itertools.product(range(3), repeat=4)]
    valid = [m for m in mappings if feasible(m)]
    assert len(valid) == 72
    assert min(map(power, valid)) == 250
    for mapping in mappings:
        repaired = repair([(h+.5)/3 for h in mapping])
        assert repaired in valid
        if feasible(mapping):
            assert mapping == repaired
    assert repair([float('nan'), 0, 0, 0]) is None
    assert repair([-1, 1, 2, 0]) == [0, 2, 2, 0]
    assert repair([0, 0, 0, 0]) == [0, 0, 1, 1]
    assert repair([0]*11) is None
    # Zero reciprocal denominator is finite with factor -2, invalid on overflow;
    # zero scalar denominator has no epsilon and produces IEEE nonfinite output.
    assert math.isfinite(defense_equation([0], [0], [1], 2, 1, 2, 0, [0])[0])
    assert not math.isfinite(defense_equation([0], [0], [1], 4, 1.5, 2, 0, [0])[0])
    g = 2*(5/12)-1
    assert not math.isfinite(defense_equation([.5], [0], [1], 2, 2*math.cos(2*math.pi*g), 2, g)[0])
    coverage = set()
    for algo in ('HO', 'GA'):
        for offset in (0, 17, 49):
            rows, best, _, covered = reference(algo, 4, 4, offset)
            coverage.update(covered)
            assert best['mapping'] in valid and best['fitness'] >= 250
            assert not any(r['accepted'] for r in rows if r['phase'] == 'PREDATOR')
    required = {'warm', 'cool-near', 'cool-restart', 'gaussian-redraw',
                ('predator-better', True), ('predator-better', False)}
    required.update(('h', k) for k in range(5))
    required.update(('escape', k) for k in range(3))
    required.update((phase, acceptance) for phase in ('RIVER_MALE', 'RIVER_FEMALE', 'DEFENSE', 'ESCAPE')
                    for acceptance in (True, False))
    assert required <= coverage, required - coverage
    print('Independent equations, repair, exhaustive 81 mappings (72 feasible): PASS')


if __name__ == '__main__':
    self_check()
    for path in sys.argv[1:]:
        with open(path, encoding='utf-8') as stream:
            fixture = json.load(stream)
        rows, best, counts, _ = reference(fixture['algorithm'], fixture['n'], fixture['t'], fixture['offset'])
        compare(fixture['trace'], rows)
        compare(fixture['best'], dict(genes=best['x'], plan=best['mapping'], watts=best['fitness'], creation=best['id']))
        assert fixture['draws'] == list(counts), (fixture['draws'], counts)
        print(f'{path}: {len(rows)} complete evaluation rows, best and draw counts PASS')
