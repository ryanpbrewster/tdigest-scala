import matplotlib.pyplot as plt
import numpy as np
import sys

data = np.loadtxt(sys.argv[1])

plt.xlabel("q")
plt.ylabel("err")
plt.plot(data[:,0], data[:,1], color="blue")
plt.plot(data[:,0], data[:,2], color="green")
plt.show()
